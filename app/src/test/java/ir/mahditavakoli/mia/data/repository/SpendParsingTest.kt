package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.SpendRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spend screen has no database behind it: it reads the numbers back out of text the workflow
 * scripts wrote. That makes these strings a contract, so the fixtures below are copied from the
 * templates in `app/src/main/assets/` — `token-usage.js` for the trailer, and the `spendFooter()`
 * of `ai-role-review.js`, `decompose-brief.js` and `qc-review.js` for the comments.
 *
 * If a script is reworded, one of these fails — which is the point. The alternative is a screen
 * that silently reads zero.
 */
class SpendParsingTest {

    // --- commit trailers (token-usage.js) ---------------------------------------------------

    @Test
    fun `reads the trailer token-usage js writes`() {
        val parsed = SpendParsing.parseCommitTrailer(
            "tec: resolve #12 — صفحه ورود\n\n" +
                "Token-Spend: 41,083 tokens (\$0.0000) via openrouter/minimax/minimax-m3:free"
        )

        assertEquals(SpendRole.TEC, parsed?.role)
        assertEquals(41_083, parsed?.tokens)
        assertEquals(0.0, parsed?.costUsd)
        assertEquals("openrouter/minimax/minimax-m3:free", parsed?.model)
    }

    @Test
    fun `reads a trailer that reports real money`() {
        val parsed = SpendParsing.parseCommitTrailer(
            "Token-Spend: 9,001 tokens (\$0.0123) via openrouter/anthropic/claude"
        )

        assertEquals(9_001, parsed?.tokens)
        assertEquals(0.0123, parsed?.costUsd!!, 1e-9)
    }

    /** `buildTrailer` drops the money when `/api/v1/key` could not be read. */
    @Test
    fun `a trailer without a cost keeps its tokens and reports an unknown cost`() {
        val parsed = SpendParsing.parseCommitTrailer(
            "Token-Spend: 1,500 tokens via openrouter/minimax/minimax-m3:free"
        )

        assertEquals(1_500, parsed?.tokens)
        // Unknown, which the screen must not render as a free run.
        assertNull(parsed?.costUsd)
    }

    /**
     * "tokens unavailable" is a spend of unknown size, not a spend of zero — counting it as 0
     * would quietly shrink every total it appears in.
     */
    @Test
    fun `an unavailable count is skipped rather than counted as zero`() {
        assertNull(
            SpendParsing.parseCommitTrailer(
                "Token-Spend: tokens unavailable (\$0.0000) via openrouter/minimax/minimax-m3:free"
            )
        )
    }

    @Test
    fun `an ordinary commit has no trailer`() {
        assertNull(SpendParsing.parseCommitTrailer("Improve issue ordering"))
    }

    // --- which issue a commit was for -------------------------------------------------------

    @Test
    fun `finds the issue number in the subject the agent writes`() {
        assertEquals(12, SpendParsing.issueNumberFrom("tec: resolve #12 — صفحه ورود"))
        assertEquals(7, SpendParsing.issueNumberFrom("Resolves #7"))
        assertEquals(3, SpendParsing.issueNumberFrom("resolved #3 at last"))
        assertNull(SpendParsing.issueNumberFrom("Merge pull request #8 from x"))
    }

    @Test
    fun `reads the issue number off a comment's issue url`() {
        assertEquals(
            12,
            SpendParsing.issueNumberFromUrl("https://api.github.com/repos/o/r/issues/12")
        )
        assertNull(SpendParsing.issueNumberFromUrl(null))
    }

    // --- role comment footers ---------------------------------------------------------------

    @Test
    fun `reads the decomposition footer as PO spend`() {
        val parsed = SpendParsing.parseRoleComment(
            "### 🗺️ Plan\n\n- #4\n\n---\n\n" +
                "🧾 **Spend for this decomposition** — 2,406 tokens · \$0.00 (free model) · " +
                "`minimax/minimax-m3:free`"
        )

        assertEquals(1, parsed.size)
        assertEquals(SpendRole.PO, parsed[0].role)
        assertEquals(2_406, parsed[0].tokens)
        assertEquals("minimax/minimax-m3:free", parsed[0].model)
        assertEquals(0.0, parsed[0].costUsd!!, 1e-9)
    }

    @Test
    fun `reads the review footer as QC spend`() {
        val parsed = SpendParsing.parseRoleComment(
            "### ✅ QC verdict\n\n| criterion | met |\n\n---\n\n" +
                "🧾 **Spend for this review** — 5,120 tokens · \$0.0044 · `minimax/minimax-m3:free`"
        )

        assertEquals(1, parsed.size)
        assertEquals(SpendRole.QC, parsed[0].role)
        assertEquals(5_120, parsed[0].tokens)
        assertEquals(0.0044, parsed[0].costUsd!!, 1e-9)
    }

    @Test
    fun `an advisory reply from one role credits only that role`() {
        val parsed = SpendParsing.parseRoleComment(
            "### 🧭 Product Owner (PO)\n\n…answer…\n\n---\n\n" +
                "🧾 **Spend for this reply** — 1,200 tokens · \$0.00 (free model) · `m3:free`"
        )

        assertEquals(listOf(SpendRole.PO), parsed.map { it.role })
        assertEquals(1_200, parsed[0].tokens)
    }

    /**
     * `ai-role-review.js` answers `@po` and `@qc` in one comment under one combined footer, so the
     * only honest options are to split it or to drop it. It splits.
     */
    @Test
    fun `a reply from both roles splits the tokens between them`() {
        val parsed = SpendParsing.parseRoleComment(
            "### 🧭 Product Owner (PO)\n\n…\n\n### ✅ QC Team\n\n…\n\n---\n\n" +
                "🧾 **Spend for this reply** — 2,406 tokens · \$0.0100 · `m3:free`"
        )

        assertEquals(listOf(SpendRole.PO, SpendRole.QC), parsed.map { it.role })
        assertEquals(listOf(1_203, 1_203), parsed.map { it.tokens })
        assertEquals(0.005, parsed[0].costUsd!!, 1e-9)
    }

    @Test
    fun `a footer with no role heading is not attributed to anyone`() {
        assertTrue(
            SpendParsing.parseRoleComment(
                "…\n\n---\n\n🧾 **Spend for this reply** — 900 tokens · \$0.00 · `m3:free`"
            ).isEmpty()
        )
    }

    /**
     * TEC's own `### 💸 Token spend` comment is the same spend as its commit trailer. Reading both
     * would double every agent run, so this comment must parse to nothing.
     */
    @Test
    fun `TEC's spend comment is left to the commit trailer`() {
        assertTrue(
            SpendParsing.parseRoleComment(
                "### 💸 Token spend for #12\n\n| | tokens |\n| --- | ---: |\n" +
                    "| **Total** | **41,083** |\n\nModel `minimax/minimax-m3:free` · 2 model calls"
            ).isEmpty()
        )
    }

    @Test
    fun `an ordinary human comment has nothing to read`() {
        assertTrue(SpendParsing.parseRoleComment("این تسک رو فردا انجام بده").isEmpty())
    }
}
