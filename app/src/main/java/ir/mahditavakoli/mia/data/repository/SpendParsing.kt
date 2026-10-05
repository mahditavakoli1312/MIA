package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.SpendRole

/**
 * Reads spend back out of the text the agents leave behind.
 *
 * There is no database of what the AI team costs. Every actor reports its own spend where the work
 * happened — TEC as a `Token-Spend:` trailer on the commit it merged, the PO and QC roles as a
 * footer on the comment they posted — and this file turns that text back into numbers.
 *
 * Which makes these functions a **contract with `.github/scripts/`**. The exact strings the
 * workflows write are asserted in `SpendParsingTest`, so a reworded footer fails a test here rather
 * than silently zeroing a screen.
 *
 * Pure string functions, deliberately: parsing is the part most likely to be wrong, and this way it
 * is the part that is tested.
 */
object SpendParsing {

    /** One spend read out of a commit message or a comment body. */
    data class Parsed(
        val role: SpendRole,
        val model: String,
        val tokens: Int,
        val costUsd: Double?
    )

    /**
     * `Token-Spend: 13,500 tokens ($0.0123) via openrouter/minimax/minimax-m3:free`
     *
     * Written by `token-usage.js` as a git trailer on the commit TEC merges, which is the only
     * record of an agent run that survives the branch being deleted and the logs ageing out.
     * "tokens unavailable" is a real value it writes when the runtime reported no breakdown — that
     * is a spend of unknown size, not a spend of zero, so it is skipped rather than counted as 0.
     */
    fun parseCommitTrailer(message: String): Parsed? {
        val match = TRAILER.find(message) ?: return null
        val tokens = match.groupValues[1].replace(",", "").toIntOrNull() ?: return null
        return Parsed(
            role = SpendRole.TEC,
            model = match.groupValues[3].trim().ifEmpty { "نامشخص" },
            tokens = tokens,
            costUsd = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        )
    }

    /** `resolve #12` / `Resolves #12` — which issue a commit was for. */
    fun issueNumberFrom(message: String): Int? =
        ISSUE_REF.find(message)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The footers the role scripts post on an issue.
     *
     * Three shapes, one per script, and each one names its role unambiguously except the advisory
     * reply — `ai-role-review.js` answers `@po` and `@qc` in a single comment with one combined
     * footer. When both roles are in it the tokens are split evenly between them, which is an
     * approximation the screen would rather make than either drop the spend or credit it all to one
     * role that did half the work.
     *
     * TEC's own `### 💸 Token spend` comment is deliberately NOT read here: it is the same spend as
     * the commit trailer, and counting both would double every agent run.
     */
    fun parseRoleComment(body: String): List<Parsed> {
        val decomposition = SPEND_FOOTER.find(body)?.takeIf { body.contains(DECOMPOSITION_HEADER) }
        if (decomposition != null) {
            return listOf(parsedFrom(SpendRole.PO, decomposition))
        }
        val review = SPEND_FOOTER.find(body)?.takeIf { body.contains(REVIEW_HEADER) }
        if (review != null) {
            return listOf(parsedFrom(SpendRole.QC, review))
        }
        val reply = SPEND_FOOTER.find(body)?.takeIf { body.contains(REPLY_HEADER) } ?: return emptyList()
        val roles = buildList {
            if (body.contains(PO_SECTION)) add(SpendRole.PO)
            if (body.contains(QC_SECTION)) add(SpendRole.QC)
        }
        val parsed = parsedFrom(SpendRole.PO, reply)
        return when (roles.size) {
            0 -> emptyList() // A reply with a footer but no role heading is not attributable.
            1 -> listOf(parsed.copy(role = roles.single()))
            else -> roles.map { role ->
                parsed.copy(
                    role = role,
                    tokens = parsed.tokens / roles.size,
                    costUsd = parsed.costUsd?.div(roles.size)
                )
            }
        }
    }

    private fun parsedFrom(role: SpendRole, match: MatchResult) = Parsed(
        role = role,
        model = match.groupValues[3].trim().ifEmpty { "نامشخص" },
        tokens = match.groupValues[1].replace(",", "").toIntOrNull() ?: 0,
        costUsd = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    )

    /** ".../issues/12" → 12, for a comment read from the repo-wide endpoint. */
    fun issueNumberFromUrl(issueUrl: String?): Int? =
        issueUrl?.substringAfterLast('/')?.toIntOrNull()

    // `Token-Spend: 13,500 tokens ($0.0123) via model` — the money part is optional.
    private val TRAILER = Regex(
        """Token-Spend:\s*([\d,]+)\s*tokens(?:\s*\(\$([\d.]+)\))?\s*via\s*(\S+)"""
    )
    private val ISSUE_REF = Regex("""(?i)resolve[sd]?\s*#(\d+)""")

    // `🧾 **Spend for this reply** — 1,234 tokens · $0.0012 · `model`` (the money may be
    // "$0.00 (free model)" or a real number; either way the digits are what matter).
    private val SPEND_FOOTER = Regex(
        """Spend for this [a-z]+\*\*\s*—\s*([\d,]+)\s*tokens\s*·\s*\$?([\d.]+)[^·]*·\s*`([^`]+)`"""
    )
    private const val REPLY_HEADER = "Spend for this reply"
    private const val DECOMPOSITION_HEADER = "Spend for this decomposition"
    private const val REVIEW_HEADER = "Spend for this review"
    private const val PO_SECTION = "Product Owner (PO)"
    private const val QC_SECTION = "QC Team"
}
