package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.agentModelByOpenCodeAddress
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import ir.mahditavakoli.mia.network.openrouter.providerFor

/**
 * Reading and rewriting the model a role runs on, inside the text of a repo's AI-team files.
 *
 * It is a pure text object with no network in it, and that is the point: the same rewrite has to
 * happen in two places that otherwise share nothing. [AgentModelMigrator] applies it to files it
 * has just read back from an existing repo, and [ir.mahditavakoli.mia.network.NetworkModule]
 * applies it to the bundled assets *before* they are uploaded into a repo that does not exist
 * yet — which is how a per-role default set in the app lands in a new project without a second
 * round-trip to GitHub after bootstrap.
 *
 * ## What a default looks like
 *
 * Every role-bearing occurrence in a workflow is a three-rung ladder:
 *
 * ```yaml
 * AGENT_MODEL_QC: ${{ vars.AGENT_MODEL_QC || vars.AGENT_MODEL || 'minimax/minimax-m3:free' }}
 * ```
 *
 * A repo variable wins if the user set one on GitHub; the repo-wide `AGENT_MODEL` is the
 * fallback that keeps a pre-role repo working; and the literal at the end is the only part MIA
 * writes. Rewriting the literal rather than setting the variable is deliberate and is explained
 * at length in [AgentModelMigrator] — the short version is that one variable cannot serve both
 * OpenCode (which needs a `provider/model` address) and the role scripts (which must not have
 * the prefix), while a per-occurrence literal keeps whichever form its own reader needs.
 */
object AgentTeamFiles {

    /**
     * The files that carry a model default, and which role owns each one when it turns out to
     * predate role scoping entirely.
     *
     * The *primary* role is only consulted on that legacy path: a file written by the current
     * bootstrap carries one explicitly-named ladder per role it runs (agent-issue-worker.yml
     * carries TEC's and, for its inline merge gate, QC's), so nothing has to be inferred. An
     * older file has a single unnamed `AGENT_MODEL` shared by everything in it, and the best
     * available answer is "whatever this file is mostly for" — see [applyRoleModels].
     */
    val MODEL_BEARING_PATHS: Map<String, AgentRole> = linkedMapOf(
        ".github/workflows/agent-issue-worker.yml" to AgentRole.TEC,
        ".github/workflows/ai-role-review.yml" to AgentRole.PO,
        ".github/workflows/qc-review.yml" to AgentRole.QC,
        ".github/workflows/decompose-brief.yml" to AgentRole.BRIEF,
        // Pre-role repos put the PO/QC default in the script itself rather than in the workflow
        // that calls it. Current ones do not, so this path is only ever a legacy rewrite.
        ".github/scripts/ai-role-review.js" to AgentRole.PO
    )

    /** OpenCode addresses models as `provider/model`, splitting on the first `/`. */
    const val OPENCODE_PREFIX = "openrouter/"

    /**
     * [role]'s own model default: `vars.AGENT_MODEL_QC || … || '<here>'`.
     *
     * The middle of the ladder is matched as "anything that isn't a quote" rather than spelled
     * out, so adding another fallback rung to the workflows later does not silently stop the app
     * from finding the literal it has to rewrite.
     */
    private fun modelPattern(role: AgentRole): Regex? =
        role.modelVariable?.let { Regex("""(vars\.$it\s*\|\|[^'\n]*')([^'\n]+)(')""") }

    private fun providerPattern(role: AgentRole): Regex? =
        role.providerVariable?.let { Regex("""(vars\.$it\s*\|\|[^'\n]*')([^'\n]+)(')""") }

    /**
     * The two shapes a *pre-role* default is written in — `${{ vars.AGENT_MODEL || '…' }}` in
     * the workflows and `process.env.AGENT_MODEL || "…"` in the scripts.
     *
     * These also match inside a current file, because the role ladder contains the bare
     * `vars.AGENT_MODEL || '…'` as its own second rung. That overlap is harmless and never
     * reached: [applyRoleModels] only falls back to them for a file where no role pattern
     * matched at all.
     */
    private val LEGACY_MODEL_DEFAULTS = listOf(
        Regex("""(vars\.AGENT_MODEL\s*\|\|\s*')([^'\n]+)(')"""),
        Regex("""(process\.env\.AGENT_MODEL\s*\|\|\s*")([^"\n]+)(")""")
    )

    private val LEGACY_PROVIDER_DEFAULTS = listOf(
        Regex("""(vars\.AGENT_PROVIDER\s*\|\|\s*')([^'\n]+)(')"""),
        Regex("""(process\.env\.AGENT_PROVIDER\s*\|\|\s*")([^"\n]+)(")""")
    )

    /** What one file's rewrite did, so the caller can report it per file and per role. */
    data class Rewrite(
        /** The file's new text — identical to the input when nothing needed changing. */
        val text: String,
        /** Roles whose own named default was found and is now correct in this file. */
        val roles: Set<AgentRole>,
        /**
         * True when this file carried no role-scoped default and was rewritten through its
         * shared `AGENT_MODEL` one instead. Every role in such a file necessarily gets the same
         * model, which is what the UI has to warn about.
         */
        val viaLegacyDefault: Boolean,
        /** True when the file carries no model default of any generation — not ours to touch. */
        val hasNoModelDefault: Boolean,
        /** True when a model default was found but no provider default to move with it. */
        val missingProviderDefault: Boolean,
        /**
         * True when this file has only a shared pre-role default and the roles being changed do
         * not include the one it belongs to — so nothing can be written without also moving a
         * role the user did not ask to move. Nothing is changed, and the caller reports it:
         * silently doing nothing here would leave the user believing a role had moved.
         */
        val blockedByShared: Boolean = false
    )

    /**
     * Returns [text] with each role in [models] repointed at its model, plus what that took.
     *
     * A role missing from [models] is left exactly as it is, so a screen that changes one role
     * writes one role. Provider defaults move with their model automatically — the provider is a
     * property of the id ([providerFor]), never a separate choice, because a mismatched pair
     * spends the wrong key and fails on the next run rather than here.
     */
    fun applyRoleModels(
        text: String,
        models: Map<AgentRole, String>,
        primaryRole: AgentRole
    ): Rewrite {
        var out = text
        val matched = mutableSetOf<AgentRole>()
        var missingProvider = false

        for ((role, model) in models) {
            val pattern = modelPattern(role) ?: continue
            val provider = providerFor(model)
            var found = false
            out = pattern.replace(out) { match ->
                found = true
                val (_, open, old, close) = match.groupValues
                open + inTheFormOf(old, model, provider) + close
            }
            if (!found) continue
            matched += role

            val providerPattern = providerPattern(role)
            var providerFound = false
            if (providerPattern != null) {
                out = providerPattern.replace(out) { match ->
                    providerFound = true
                    val (_, open, _, close) = match.groupValues
                    open + provider.id + close
                }
            }
            if (!providerFound) missingProvider = true
        }

        if (matched.isNotEmpty()) {
            return Rewrite(
                text = out,
                roles = matched,
                viaLegacyDefault = false,
                hasNoModelDefault = false,
                missingProviderDefault = missingProvider
            )
        }

        // No role ladder anywhere in this file: it was bootstrapped before roles existed. Its one
        // shared default is rewritten with the model of the role the file is mostly for, and the
        // caller is told so — this is the case where "a model per role" cannot be honoured, and a
        // user who asked for two different models deserves to hear that instead of guessing.
        val model = models[primaryRole] ?: return Rewrite(
            text = text,
            roles = emptySet(),
            viaLegacyDefault = false,
            hasNoModelDefault = !hasAnyModelDefault(text),
            missingProviderDefault = false,
            // A shared default belongs to every role in the file at once. Moving it for a role
            // the user did name would drag the primary role along with it, which is a change
            // they did not ask for — so this file is left alone and said out loud instead.
            blockedByShared = models.isNotEmpty() &&
                LEGACY_MODEL_DEFAULTS.any { it.containsMatchIn(text) }
        )
        val legacy = applyLegacyModel(text, model)
            ?: return Rewrite(
                text = text,
                roles = emptySet(),
                viaLegacyDefault = false,
                hasNoModelDefault = true,
                missingProviderDefault = false
            )
        return Rewrite(
            text = legacy,
            roles = setOf(primaryRole),
            viaLegacyDefault = true,
            hasNoModelDefault = false,
            missingProviderDefault = LEGACY_PROVIDER_DEFAULTS.none { it.containsMatchIn(text) }
        )
    }

    /** Rewrites the shared pre-role default, or null when the file carries none. */
    private fun applyLegacyModel(text: String, model: String): String? {
        val provider = providerFor(model)
        var matched = false
        var out = text
        for (pattern in LEGACY_MODEL_DEFAULTS) {
            out = pattern.replace(out) { match ->
                matched = true
                val (_, open, old, close) = match.groupValues
                open + inTheFormOf(old, model, provider) + close
            }
        }
        if (!matched) return null
        for (pattern in LEGACY_PROVIDER_DEFAULTS) {
            out = pattern.replace(out) { match ->
                val (_, open, _, close) = match.groupValues
                open + provider.id + close
            }
        }
        return out
    }

    private fun hasAnyModelDefault(text: String): Boolean =
        LEGACY_MODEL_DEFAULTS.any { it.containsMatchIn(text) } || hasAnyRoleDefault(text)

    /**
     * The model this file names for [role], stripped of any provider prefix so the answer is
     * comparable with an [ir.mahditavakoli.mia.network.openrouter.AgentModel.id] — or null when
     * this file says nothing about that role.
     *
     * The shared pre-role default is only consulted for a file that carries **no** role-scoped
     * ladder at all, and that condition is the whole subtlety here. On such a file the shared
     * default genuinely is every role's model: the whole team was running on it. On a current
     * file it is merely the middle rung of somebody else's ladder — `agent-issue-worker.yml`
     * holds TEC's and QC's and says nothing about the PO — and reading it as the PO's answer
     * would report the wrong model for a role that file does not run.
     */
    fun readRoleModel(text: String, role: AgentRole): String? {
        val own = modelPattern(role)?.find(text)?.groupValues?.get(2)
        if (own != null) return unqualify(own)
        if (hasAnyRoleDefault(text)) return null
        val legacy = LEGACY_MODEL_DEFAULTS.firstNotNullOfOrNull {
            it.find(text)?.groupValues?.get(2)
        }
        return legacy?.let(::unqualify)
    }

    private fun hasAnyRoleDefault(text: String): Boolean =
        AgentRole.REPO_ROLES.any { role -> modelPattern(role)?.containsMatchIn(text) == true }

    /**
     * The model id written the way *this particular occurrence* writes them: OpenCode's
     * `provider/<id>` address in the TEC workflow, the bare id everywhere a provider API is
     * called directly. Decided from the value being replaced, so one file can hold both.
     */
    private fun inTheFormOf(old: String, model: String, provider: AgentProvider): String =
        if (isQualified(old)) provider.openCodeAddress(model) else model

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
}
