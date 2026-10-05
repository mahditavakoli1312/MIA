package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.openrouter.AgentRole

/**
 * Brings an existing repo's `.github` AI-team files up to the version this build of MIA ships.
 *
 * Every repo owns its **own copy** of the workflows and role scripts — MIA commits them at
 * bootstrap and never looks at them again — which is what makes each project independent, and
 * also what makes them go stale. A repo created before role-scoped models has one shared
 * `AGENT_MODEL` and cannot put QC on a different model than TEC. One created before the QC↔PO↔TEC
 * loop hands a twice-rejected issue to `needs-human` and stops. Until now the only fix MIA could
 * offer was "copy `docs/github/` in by hand", which is not a fix a phone can carry out.
 *
 * ## What it will and will not touch
 *
 * Only files under `.github/` — the workflows and the role scripts, the machinery MIA wrote and
 * maintains. Deliberately **not** `AGENTS.md` (the conventions file, which a project is expected
 * to make its own) and **not** the `mia/design` sources (a starting point, not a managed asset).
 * Overwriting either would be MIA taking back something it gave away, and neither carries any of
 * the role machinery this exists to update.
 *
 * ## What it preserves
 *
 * The models. A repo whose QC sits on a big model and whose TEC sits on a free one must still be
 * that way afterwards, so the current per-role models are read from the repo first and written
 * into the new files; only a role the repo names nowhere falls back to the app's default. Getting
 * this wrong would silently move a paid model onto every role, or a free one onto a role the user
 * deliberately upgraded — an expensive surprise either way.
 *
 * Role *prompts* are a different matter: they live inside the managed scripts, so a user who
 * edited one loses that edit. The screen says so before the update runs, because it is the one
 * consequence a reader would not predict.
 */
class TeamFilesUpdater(
    private val api: GitHubApi,
    private val base64: Base64Encoder,
    private val base64Decoder: Base64Decoder,
    /** The files this build ships, already carrying the app's per-role default models. */
    private val currentFiles: () -> List<BootstrapFile>,
    private val migrator: AgentModelMigrator
) {

    /**
     * @param updated files whose content differed and were committed.
     * @param unchanged files this repo already had byte-for-byte.
     * @param added files the repo did not have at all — an older bootstrap shipped fewer, and
     *        reported apart from [updated] because "you now have a QC gate you did not have"
     *        is a bigger piece of news than "your QC gate is newer".
     * @param failed path → why, for files that could not be written.
     * @param models the per-role models carried across, so the UI can say what was preserved.
     */
    data class Outcome(
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val added: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList(),
        val models: Map<AgentRole, String> = emptyMap()
    ) {
        val didChange: Boolean get() = updated.isNotEmpty() || added.isNotEmpty()

        /** Everything that landed, for a one-line summary. */
        val changedCount: Int get() = updated.size + added.size
    }

    /**
     * Rewrites every managed `.github` file in [repo] to this build's version.
     *
     * Never throws: each file is committed on its own, and one that cannot be written is
     * reported rather than aborting the rest. A repo left half-updated is not a good outcome,
     * but it is a much better one than a repo where the first failure hid the other six.
     */
    suspend fun update(
        owner: String,
        repo: String,
        /** Used for any role the repo itself does not name. */
        fallbackModels: Map<AgentRole, String>
    ): Outcome {
        // Read the repo's own models BEFORE overwriting the files that hold them — afterwards
        // the answer would be whatever the new files were built with, which is the bug.
        val existing = migrator.currentModels(owner, repo)
        val models = AgentRole.REPO_ROLES.mapNotNull { role ->
            (existing[role] ?: fallbackModels[role])?.let { role to it }
        }.toMap()

        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val added = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        for (file in currentFiles()) {
            if (!file.repoPath.startsWith(MANAGED_PREFIX)) continue

            // Each file gets the models this repo actually runs, in the form its own reader
            // needs — the same rewrite the model screen does, applied to the new text.
            val role = AgentTeamFiles.MODEL_BEARING_PATHS[file.repoPath]
            val content = if (role == null) {
                file.content
            } else {
                AgentTeamFiles.applyRoleModels(file.content, models, role).text
            }

            val current = read(owner, repo, file.repoPath)
            if (current != null && current.text == content) {
                unchanged += file.repoPath
                continue
            }
            runCatching {
                val response = api.putContent(
                    owner = owner,
                    repo = repo,
                    path = file.repoPath,
                    body = PutContentBody(
                        message = "chore(mia): update ${file.repoPath} to the current AI-team files",
                        content = base64.encode(content.toByteArray(Charsets.UTF_8)),
                        // Absent for a file the repo does not have yet; GitHub reads that as
                        // "create", which is exactly right.
                        sha = current?.sha
                    )
                )
                check(response.isSuccessful) { "HTTP ${response.code()}" }
            }.fold(
                onSuccess = { if (current == null) added += file.repoPath else updated += file.repoPath },
                onFailure = { failed += file.repoPath to (it.message ?: "خطای نامشخص") }
            )
        }
        return Outcome(
            updated = updated,
            unchanged = unchanged,
            added = added,
            failed = failed,
            models = models
        )
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
        /**
         * The only tree MIA claims. Everything it installs outside it — `AGENTS.md`, the design
         * sources — is a starting point the project owns from then on.
         */
        const val MANAGED_PREFIX = ".github/"
    }
}
