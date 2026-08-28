package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.PutContentBody

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
 * and splits on the *first* `/`, so it needs `openrouter/<id>`. The PO/QC script calls the
 * OpenRouter API directly and sends the value verbatim, so it must *not* have that prefix. A
 * single variable is wrong for one of them whichever way it is written. Rewriting the defaults
 * in place lets each file keep the form it needs.
 *
 * The rewrite is deliberately narrow: only the model id inside an `AGENT_MODEL` default is
 * touched, and whatever prefix that particular occurrence already had is preserved. Nothing
 * else in the file — role prompts a user may have edited, added steps, comments — is disturbed.
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
     * @param failed path → why, for files that could not be written.
     */
    data class Outcome(
        val model: String,
        val updated: List<String> = emptyList(),
        val unchanged: List<String> = emptyList(),
        val missing: List<String> = emptyList(),
        val withoutModel: List<String> = emptyList(),
        val failed: List<Pair<String, String>> = emptyList()
    ) {
        /** True when at least one file was actually committed. */
        val didChange: Boolean get() = updated.isNotEmpty()

        /** True when the repo carries none of the OpenRouter AI-team files. */
        val isNotAnOpenRouterRepo: Boolean
            get() = updated.isEmpty() && unchanged.isEmpty() && failed.isEmpty()
    }

    /**
     * Reads the model the repo's TEC agent currently runs on, or null when the repo has no
     * workflow carrying an `AGENT_MODEL` default. The `openrouter/` prefix is stripped, so the
     * answer is comparable with an [ir.mahditavakoli.mia.network.openrouter.AgentModel.id].
     */
    suspend fun currentModel(owner: String, repo: String): String? {
        for (path in MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path) ?: continue
            val found = MODEL_DEFAULTS.firstNotNullOfOrNull { it.find(file.text)?.groupValues?.get(2) }
            if (found != null) return found.removePrefix(OPENCODE_PREFIX)
        }
        return null
    }

    /** Points every AI-team file in [repo] at [model]. Never throws; failures land in [Outcome]. */
    suspend fun setModel(owner: String, repo: String, model: String): Outcome {
        val updated = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val withoutModel = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        for (path in MODEL_BEARING_PATHS) {
            val file = read(owner, repo, path)
            if (file == null) {
                missing += path
                continue
            }
            val rewritten = rewrite(file.text, model)
            when {
                rewritten == null -> withoutModel += path
                rewritten == file.text -> unchanged += path
                else -> runCatching {
                    val response = api.putContent(
                        owner = owner,
                        repo = repo,
                        path = path,
                        body = PutContentBody(
                            message = "chore(mia): point the AI team at $model",
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
        return Outcome(model, updated, unchanged, missing, withoutModel, failed)
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
     * Returns [text] with every `AGENT_MODEL` default repointed at [model], or null when the
     * file carries no such default (so the caller can tell "nothing to do" apart from "already
     * correct"). Each occurrence keeps the prefix it had: OpenCode's `openrouter/<id>` form in
     * the TEC workflow, the bare id in the PO/QC script.
     */
    private fun rewrite(text: String, model: String): String? {
        var matched = false
        var out = text
        for (pattern in MODEL_DEFAULTS) {
            out = pattern.replace(out) { match ->
                matched = true
                val (_, open, old, close) = match.groupValues
                val replacement = if (old.startsWith(OPENCODE_PREFIX)) OPENCODE_PREFIX + model else model
                open + replacement + close
            }
        }
        return out.takeIf { matched }
    }

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
    }
}
