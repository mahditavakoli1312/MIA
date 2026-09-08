package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.agentModelByOpenCodeAddress
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import ir.mahditavakoli.mia.network.openrouter.providerFor

/**
 * Repoints an already-bootstrapped repo's AI team at another model.
 *
 * MIA pushes the workflow files into a repo when it creates it, so each repo owns its **own
 * copy** and the model id is frozen into it at bootstrap time. Changing the default in the app
 * therefore does nothing to repos that already exist — and when a model is withdrawn (as
 * `stealth/ox-alpha` and the `openai/gpt-oss-*` defaults before it both were) every `@tec`,
 * `@po` and `@qc` run in those repos fails until the files themselves are edited. That is what
 * this does, one repo at a time, from the project card.
 *
 * **Why not just set an `AGENT_MODEL` Actions variable?** Because one variable cannot serve both
 * readers. TEC reaches the model through OpenCode, which addresses models as `provider/model`
 * and splits on the *first* `/`, so it needs `openrouter/<id>` (or `minimax/<id>`). The PO/QC
 * script calls the provider's API directly and sends the value verbatim, so it must *not* have
 * that prefix. A single variable is wrong for one of them whichever way it is written. Rewriting
 * the defaults in place lets each file keep the form it needs.
 *
 * Since a model can now be reached through more than one service, an `AGENT_PROVIDER` default
 * is rewritten alongside it — that is what tells each file which API key to spend and, for the
 * PO/QC script, which host to call. Repos bootstrapped before MiniMax support shipped carry no
 * such default; they are reported as [Outcome.withoutProvider] so the UI can say the files need
 * refreshing rather than silently leaving them pointed at OpenRouter with a MiniMax model id.
 *
 * The rewrite is deliberately narrow: only the ids inside an `AGENT_MODEL` / `AGENT_PROVIDER`
 * default are touched, and whatever prefix that particular occurrence already had is preserved.
 * Nothing else in the file — role prompts a user may have edited, added steps, comments — is
 * disturbed.
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
     * @param updated files rewritten and committed.
     * @param unchanged files that already named the requested model.
     * @param missing files this repo doesn't have (an older bootstrap shipped fewer).
     * @param withoutModel files present but carrying no `AGENT_MODEL` default at all — the
     *        first generation of workflows ran on the Gemini CLI and has nothing to rewrite.
     * @param withoutProvider files carrying a model default but no `AGENT_PROVIDER` one, from a
     *        bootstrap that predates multi-provider support. Only interesting when the chosen
     *        model is *not* served by [AgentProvider.DEFAULT]: such a file will keep reaching for
     *        OpenRouter with an id OpenRouter does not have.
     * @param failed path → why, for files that could not be written.
     */
    data class Outcome(
        val model: String,
        val provider: AgentProvider = AgentProvider.DEFAULT,
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val withoutModel: List<String> = emptyList(),
        val withoutProvider: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList()
    ) {
        /** True when at least one file was actually committed. */
        val didChange: Boolean get() = updated.isNotEmpty()

        /** True when the repo carries none of the model-bearing AI-team files. */
        val isNotAnOpenRouterRepo: Boolean
            get() = updated.isEmpty() && unchanged.isEmpty() && failed.isEmpty()

        /**
         * True when the repo took the model but has no `AGENT_PROVIDER` default to take with
         * it, and the model needs one. Those files will still call OpenRouter, so the change is
         * only half applied — the user has to re-create the repo (or copy the current workflow
         * files in) before a MiniMax model can actually run there.
         */
        val needsProviderAwareFiles: Boolean
            get() = provider != AgentProvider.DEFAULT && withoutProvider.isNotEmpty()
    }

    /**
     * Reads the model the repo's TEC agent currently runs on, or null when the repo has no
     * workflow carrying an `AGENT_MODEL` default. Any provider prefix is stripped, so the answer
     * is comparable with an [ir.mahditavakoli.mia.network.openrouter.AgentModel.id].
     */
    suspend fun currentModel(owner: String, repo: String): String? {
        for (path in MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path) ?: continue
            val found = MODEL_DEFAULTS.firstNotNullOfOrNull { it.find(file.text)?.groupValues?.get(2) }
            if (found != null) return unqualify(found)
        }
        return null
    }

    /** Points every AI-team file in [repo] at [model]. Never throws; failures land in [Outcome]. */
    suspend fun setModel(owner: String, repo: String, model: String): Outcome {
        val provider = providerFor(model)
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val withoutModel = mutableListOf<String>()
        val withoutProvider = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        for (path in MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path)
            if (file == null) {
                missing += path
                continue
            }
            val rewritten = rewrite(file.text, model, provider)
            if (rewritten != null && !PROVIDER_DEFAULTS.any { it.containsMatchIn(file.text) }) {
                withoutProvider += path
            }
            when {
                rewritten == null -> withoutModel += path
                rewritten == file.text -> unchanged += path
                else -> runCatching {
                    val response = api.putContent(
                        owner = owner,
                        repo = repo,
                        path = path,
                        body = PutContentBody(
                            message = "chore(mia): point the AI team at $model (${provider.id})",
                            content = base64.encode(rewritten.toByteArray(Charsets.UTF_8)),
                            sha = file.sha
                        )
                    )
                    check(response.isSuccessful) { "HTTP ${response.code()}" }
                }.fold(
                    onSuccess = { updated += path },
                    onFailure = { failed += path to (it.message ?: "خطای نامشخص") }
                )
            }
        }
        return Outcome(
            model = model,
            provider = provider,
            updated = updated,
            unchanged = unchanged,
            missing = missing,
            withoutModel = withoutModel,
            withoutProvider = withoutProvider,
            failed = failed
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

    /**
     * Returns [text] with every `AGENT_MODEL` default repointed at [model] (and every
     * `AGENT_PROVIDER` default at [provider]), or null when the file carries no model default at
     * all — so the caller can tell "nothing to do" apart from "already correct".
     *
     * Each model occurrence keeps the *form* it had: OpenCode's `provider/<id>` address in the
     * TEC workflow, the bare id everywhere the provider API is called directly. Whether an
     * existing value was qualified is decided by [isQualified], not by looking for a slash —
     * `minimax/minimax-m3:free` is a bare OpenRouter id that happens to look like one.
     */
    private fun rewrite(text: String, model: String, provider: AgentProvider): String? {
        var matched = false
        var out = text
        for (pattern in MODEL_DEFAULTS) {
            out = pattern.replace(out) { match ->
                matched = true
                val (_, open, old, close) = match.groupValues
                val replacement = if (isQualified(old)) provider.openCodeAddress(model) else model
                open + replacement + close
            }
        }
        // Only meaningful once a model default was found: a file with neither is not ours.
        if (!matched) return null
        for (pattern in PROVIDER_DEFAULTS) {
            out = pattern.replace(out) { match ->
                val (_, open, _, close) = match.groupValues
                open + provider.id + close
            }
        }
        return out
    }

    /**
     * Whether [value] is an OpenCode `provider/model` address rather than a bare model id.
     *
     * Prefix matching cannot answer this — both `minimax/MiniMax-M3` (qualified) and
     * `minimax/minimax-m3:free` (a bare OpenRouter id) start with `minimax/` — so the known
     * model table decides it, and only a model MIA has never heard of (hand-edited, or since
     * withdrawn) falls back to the one prefix MIA used to write.
     */
    private fun isQualified(value: String): Boolean = when {
        agentModelByOpenCodeAddress(value) != null -> true
        agentModelOrNull(value) != null -> false
        else -> value.startsWith(OPENCODE_PREFIX)
    }

    /** The inverse: a `provider/model` address reduced to the plain model id. */
    private fun unqualify(value: String): String =
        agentModelByOpenCodeAddress(value)?.id
            ?: agentModelOrNull(value)?.id
            ?: value.removePrefix(OPENCODE_PREFIX)

    companion object {
        /** OpenCode addresses models as `provider/model`, splitting on the first `/`. */
        const val OPENCODE_PREFIX = "openrouter/"

        /**
         * The AI-team files that name a model. `token-usage.js` reads `AGENT_MODEL` too, but
         * only to print it — the workflow passes it in, so it carries no default of its own.
         */
        val MODEL_BEARING_PATHS = listOf(
            ".github/workflows/agent-issue-worker.yml",
            ".github/workflows/ai-role-review.yml",
            ".github/scripts/ai-role-review.js"
        )

        /**
         * The two shapes a default is written in — `${{ vars.AGENT_MODEL || '…' }}` in the
         * workflows and `process.env.AGENT_MODEL || "…"` in the script. Matching the *default*
         * rather than a known model id is what makes this work on every generation of repo,
         * including ones already pointing at a model MIA no longer offers.
         */
        val MODEL_DEFAULTS = listOf(
            Regex("""(vars\.AGENT_MODEL\s*\|\|\s*')([^']+)(')"""),
            Regex("""(process\.env\.AGENT_MODEL\s*\|\|\s*")([^"]+)(")""")
        )

        /**
         * The same two shapes for the companion `AGENT_PROVIDER` default, which says *which
         * service* the model id belongs to. Written unqualified in every file — it is a provider
         * name, not an address — and absent entirely from repos bootstrapped before MiniMax
         * support, which is what [Outcome.withoutProvider] reports.
         */
        val PROVIDER_DEFAULTS = listOf(
            Regex("""(vars\.AGENT_PROVIDER\s*\|\|\s*')([^']+)(')"""),
            Regex("""(process\.env\.AGENT_PROVIDER\s*\|\|\s*")([^"]+)(")""")
        )
    }
}
