package ir.mahditavakoli.mia.data.model

import java.util.Locale

/**
 * Tokens billed for a single model call, normalized across providers so every stage of the
 * pipeline reports spend the same way: MIA's own Gemini call here, the TEC coding agent and
 * the PO/QC advisors in `.github/scripts/token-usage.js`.
 *
 * Providers name these differently (Gemini: promptTokenCount/candidatesTokenCount/
 * thoughtsTokenCount, OpenRouter: prompt_tokens/completion_tokens/reasoning_tokens), so each
 * caller maps into this shape once and everything downstream formats identically.
 */
data class TokenUsage(
    val model: String,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    /** Thinking tokens: billed as output, but reported separately by models that expose them. */
    val reasoningTokens: Int = 0,
    /** Provider-reported total; falls back to the sum when a provider omits it. */
    val totalTokens: Int = promptTokens + outputTokens + reasoningTokens
) {
    /**
     * The line appended to a GitHub issue body so the task itself carries what MIA spent to
     * understand the command that created it — the first entry in that issue's spend ledger,
     * which the TEC/PO/QC agents then add to as they work on it.
     *
     * @param sharedBy how many task issues came out of this one voice command. A single spoken
     *        command can split into several issues, and the model was billed once for all of
     *        them, so the footer says so instead of counting the same tokens N times.
     */
    fun asIssueFooter(sharedBy: Int = 1): String = buildString {
        append("🧾 **MIA voice intent** · `").append(model).append("` · ")
        append(group(totalTokens)).append(" tokens")
        append(" (prompt ").append(group(promptTokens))
        append(" + output ").append(group(outputTokens))
        if (reasoningTokens > 0) append(" + thinking ").append(group(reasoningTokens))
        append(')')
        if (sharedBy > 1) {
            append(" — one voice command that opened ").append(sharedBy)
            append(" issues, so this spend is shared between them.")
        }
    }

    /** Persian one-liner for the in-app confirmation snackbar. */
    fun asPersianSummary(): String =
        "🧾 ${group(totalTokens)} توکن برای درک این دستور مصرف شد ($model)"

    private companion object {
        /** Locale.US so the thousands separator is always "," — never Persian digits. */
        fun group(value: Int): String = String.format(Locale.US, "%,d", value)
    }
}
