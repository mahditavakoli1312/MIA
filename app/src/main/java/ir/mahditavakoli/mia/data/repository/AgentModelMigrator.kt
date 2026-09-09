package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.providerFor

/**
 * Repoints an already-bootstrapped repo's AI team — one role at a time, or all of them at once.
 *
 * MIA pushes the workflow files into a repo when it creates it, so each repo owns its **own
 * copy** and the model ids are frozen into it at bootstrap time. Changing the defaults in the app
 * therefore does nothing to repos that already exist — and when a model is withdrawn (as
 * `stealth/ox-alpha` and the `openai/gpt-oss-*` defaults before it both were) every `@tec`,
 * `@po` and `@qc` run in those repos fails until the files themselves are edited. That is what
 * this does, one repo at a time, from the project's model management screen.
 *
 * **Why not just set `AGENT_MODEL_*` Actions variables?** Because one variable cannot serve both
 * readers. TEC reaches the model through OpenCode, which addresses models as `provider/model`
 * and splits on the *first* `/`, so it needs `openrouter/<id>` (or `minimax/<id>`). The role
 * scripts call the provider's API directly and send the value verbatim, so it must *not* have
 * that prefix. A single variable is wrong for one of them whichever way it is written. Rewriting
 * the defaults in place lets each file keep the form it needs — and leaves the variables free for
 * a user who wants to override MIA from GitHub's own UI, since they still win over the literal.
 *
 * Since a model can be reached through more than one service, an `AGENT_PROVIDER_*` default is
 * rewritten alongside each model — that is what tells each file which API key to spend and, for
 * the role scripts, which host to call. Repos bootstrapped before MiniMax support carry no such
 * default; they are reported as [Outcome.withoutProvider] so the UI can say the files need
 * refreshing rather than silently leaving them pointed at OpenRouter with a MiniMax model id.
 *
 * The rewrite is deliberately narrow: only the ids inside an `AGENT_MODEL*` / `AGENT_PROVIDER*`
 * default are touched, and whatever prefix that particular occurrence already had is preserved.
 * Nothing else in the file — role prompts a user may have edited, added steps, comments — is
 * disturbed. All of that text handling lives in [AgentTeamFiles]; this class is the GitHub half.
 */
class AgentModelMigrator(
    private val api: GitHubApi,
    private val base64: Base64Encoder,
    private val base64Decoder: Base64Decoder
) {

    /**
     * What one repo's migration did, per file, so the UI can say something specific instead of
     * a bare "done".
     *
     * @param models the model each role was pointed at in this run.
     * @param updated files rewritten and committed.
     * @param unchanged files that already named the requested models.
     * @param missing files this repo doesn't have (an older bootstrap shipped fewer).
     * @param withoutModel files present but carrying no model default at all — the first
     *        generation of workflows ran on the Gemini CLI and has nothing to rewrite.
     * @param withoutProvider files carrying a model default but no matching `AGENT_PROVIDER*`
     *        one, from a bootstrap that predates multi-provider support. Only interesting when a
     *        chosen model is *not* served by [AgentProvider.DEFAULT]: such a file will keep
     *        reaching for OpenRouter with an id OpenRouter does not have.
     * @param sharedAcrossRoles files that predate role-scoped models and still carry one shared
     *        `AGENT_MODEL` default. Every role in such a file runs on the same model whatever
     *        the user picked, which is the one promise this class cannot keep — see
     *        [rolesAreNotSeparable].
     * @param blockedByShared files left untouched because their one shared default belongs to a
     *        role this run was not changing: writing it would have moved that role too. The
     *        roles the user *did* ask for are simply not applied in these files, and saying so
     *        is the whole reason the list exists.
     * @param failed path → why, for files that could not be written.
     */
    data class Outcome(
        val models: Map<AgentRole, String> = emptyMap(),
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val withoutModel: List<String> = emptyList(),
        val withoutProvider: List<String> = emptyList(),
        val sharedAcrossRoles: List<String> = emptyList(),
        val blockedByShared: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList()
    ) {
        /** True when at least one file was actually committed. */
        val didChange: Boolean get() = updated.isNotEmpty()

        /** True when the repo carries none of the model-bearing AI-team files. */
        val isNotAnOpenRouterRepo: Boolean
            get() = updated.isEmpty() && unchanged.isEmpty() && failed.isEmpty()

        /** The providers the chosen models are served by — one entry per distinct provider. */
        val providers: Set<AgentProvider> get() = models.values.map(::providerFor).toSet()

        /**
         * True when the repo took the models but has no provider default to carry them, and at
         * least one of them needs one. Those files will still call OpenRouter, so the change is
         * only half applied — the user has to re-create the repo (or copy the current workflow
         * files in) before a MiniMax model can actually run there.
         */
        val needsProviderAwareFiles: Boolean
            get() = providers.any { it != AgentProvider.DEFAULT } && withoutProvider.isNotEmpty()

        /**
         * True when the user asked two roles for two different models but this repo's files are
         * too old to hold more than one. The models still land; they just cannot differ.
         */
        val rolesAreNotSeparable: Boolean
            get() = blockedByShared.isNotEmpty() ||
                (sharedAcrossRoles.isNotEmpty() && models.values.toSet().size > 1)
    }

    /**
     * The model each repo role currently runs on, read straight from the repo's files.
     *
     * A role missing from the result is one this repo says nothing about — an older bootstrap
     * without that workflow, or a repo that is not a MIA repo at all. A pre-role repo answers
     * the same model for every role, which is the truth: that is what all of them run on.
     */
    suspend fun currentModels(owner: String, repo: String): Map<AgentRole, String> {
        val found = mutableMapOf<AgentRole, String>()
        for ((path, _) in AgentTeamFiles.MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path) ?: continue
            for (role in AgentRole.REPO_ROLES) {
                if (role in found) continue
                AgentTeamFiles.readRoleModel(file.text, role)?.let { found[role] = it }
            }
        }
        return found
    }

    /**
     * Reads the model the repo's TEC agent currently runs on, or null when the repo has no
     * workflow carrying a model default. The single-model view of [currentModels], kept because
     * "what is this repo on?" is still a fair question to ask of a repo with one answer.
     */
    suspend fun currentModel(owner: String, repo: String): String? =
        currentModels(owner, repo)[AgentRole.TEC]

    /** Points every repo role in [repo] at [model]. Never throws; failures land in [Outcome]. */
    suspend fun setModel(owner: String, repo: String, model: String): Outcome =
        setModels(owner, repo, AgentRole.REPO_ROLES.associateWith { model })

    /**
     * Points each role in [models] at its own model, leaving every role not named alone.
     *
     * One commit per file, carrying every role that file holds, so pointing TEC and QC somewhere
     * else costs two commits rather than four. Never throws: a file that cannot be read or
     * written is reported in the outcome, because a repo whose workflows are half-updated still
     * needs the user to be told which half.
     */
    suspend fun setModels(
        owner: String,
        repo: String,
        models: Map<AgentRole, String>
    ): Outcome {
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val withoutModel = mutableListOf<String>()
        val withoutProvider = mutableListOf<String>()
        val sharedAcrossRoles = mutableListOf<String>()
        val blockedByShared = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        for ((path, primaryRole) in AgentTeamFiles.MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path)
            if (file == null) {
                missing += path
                continue
            }
            val rewrite = AgentTeamFiles.applyRoleModels(file.text, models, primaryRole)
            if (rewrite.hasNoModelDefault) {
                withoutModel += path
                continue
            }
            if (rewrite.roles.isEmpty()) {
                // The file carries defaults, but none for a role this run is changing — a
                // qc-review.yml while the user is only moving TEC. Nothing to do, and nothing
                // worth reporting either: it is not "unchanged when it should have changed"...
                // unless it is an old file whose one shared default could not be moved without
                // dragging another role with it, which the user has to be told about.
                if (rewrite.blockedByShared) blockedByShared += path
                continue
            }
            if (rewrite.missingProviderDefault) withoutProvider += path
            if (rewrite.viaLegacyDefault) sharedAcrossRoles += path

            if (rewrite.text == file.text) {
                unchanged += path
                continue
            }
            runCatching {
                val response = api.putContent(
                    owner = owner,
                    repo = repo,
                    path = path,
                    body = PutContentBody(
                        message = commitMessage(models.filterKeys { it in rewrite.roles }),
                        content = base64.encode(rewrite.text.toByteArray(Charsets.UTF_8)),
                        sha = file.sha
                    )
                )
                check(response.isSuccessful) { "HTTP ${response.code()}" }
            }.fold(
                onSuccess = { updated += path },
                onFailure = { failed += path to (it.message ?: "خطای نامشخص") }
            )
        }
        return Outcome(
            models = models,
            updated = updated,
            unchanged = unchanged,
            missing = missing,
            withoutModel = withoutModel,
            withoutProvider = withoutProvider,
            sharedAcrossRoles = sharedAcrossRoles,
            blockedByShared = blockedByShared,
            failed = failed
        )
    }

    /**
     * The commit subject, naming the roles this file actually carries rather than every role in
     * the run — a reader of `qc-review.yml`'s history should not see TEC mentioned.
     */
    private fun commitMessage(models: Map<AgentRole, String>): String {
        val what = models.entries.joinToString(", ") { (role, model) ->
            "${role.id}=$model (${providerFor(model).id})"
        }
        return "chore(mia): point the AI team at $what"
    }

    private data class SourceFile(val text: String, val sha: String)

    /** Null for anything that isn't a readable, inline, base64 file — 404 included. */
    private suspend fun read(owner: String, repo: String, path: String): SourceFile? = runCatching {
        val response = api.getContent(owner, repo, path)
        val body = response.body()
        if (!response.isSuccessful || body == null || body.encoding != "base64") return null
        // GitHub wraps the base64 at 60 columns; the decoder is only promised plain input.
        val bytes = base64Decoder.decode(body.content.filterNot { it.isWhitespace() })
        SourceFile(text = String(bytes, Charsets.UTF_8), sha = body.sha)
    }.getOrNull()

    companion object {
        /** Kept as the address it always was; the table itself lives in [AgentTeamFiles]. */
        const val OPENCODE_PREFIX = AgentTeamFiles.OPENCODE_PREFIX
    }
}
