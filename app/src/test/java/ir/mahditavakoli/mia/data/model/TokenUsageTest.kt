package ir.mahditavakoli.mia.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The footer produced here is committed onto a GitHub issue, where it is the first entry in
 * that task's spend ledger — so the numbers have to be right and the formatting has to stay
 * ASCII regardless of the device locale.
 */
class TokenUsageTest {

    @Test
    fun `total falls back to the sum when a provider omits it`() {
        val usage = TokenUsage(
            model = "gemini-2.5-flash",
            promptTokens = 1_200,
            outputTokens = 240,
            reasoningTokens = 60
        )

        assertEquals(1_500, usage.totalTokens)
    }

    @Test
    fun `provider-reported total wins over the sum`() {
        val usage = TokenUsage(
            model = "gemini-2.5-flash",
            promptTokens = 1_200,
            outputTokens = 240,
            totalTokens = 1_500 // provider counted something the parts don't show
        )

        assertEquals(1_500, usage.totalTokens)
    }

    @Test
    fun `footer reports the breakdown with grouped ASCII digits`() {
        val footer = TokenUsage(
            model = "gemini-2.5-flash",
            promptTokens = 12_345,
            outputTokens = 678
        ).asIssueFooter()

        assertTrue(footer, footer.contains("`gemini-2.5-flash`"))
        assertTrue(footer, footer.contains("13,023 tokens"))
        assertTrue(footer, footer.contains("prompt 12,345"))
        assertTrue(footer, footer.contains("output 678"))
        // Thinking is only mentioned when a model actually charged for it.
        assertFalse(footer, footer.contains("thinking"))
    }

    @Test
    fun `thinking tokens are broken out when present`() {
        val footer = TokenUsage(
            model = "gemini-2.5-flash",
            promptTokens = 100,
            outputTokens = 20,
            reasoningTokens = 5
        ).asIssueFooter()

        assertTrue(footer, footer.contains("thinking 5"))
        assertTrue(footer, footer.contains("125 tokens"))
    }

    @Test
    fun `one command that opens several issues reports the spend as shared`() {
        val usage = TokenUsage(model = "gemini-2.5-flash", promptTokens = 900, outputTokens = 100)

        assertFalse(usage.asIssueFooter(sharedBy = 1).contains("shared"))
        val shared = usage.asIssueFooter(sharedBy = 3)
        assertTrue(shared, shared.contains("opened 3 issues"))
        assertTrue(shared, shared.contains("shared between them"))
    }

    @Test
    fun `digits stay ASCII under a Persian default locale`() {
        val original = Locale.getDefault()
        try {
            // The app is Persian-first; a locale-aware formatter would emit ۱۲٬۳۴۵ here and
            // corrupt the issue footer for anyone parsing it.
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val footer = TokenUsage("gemini-2.5-flash", promptTokens = 12_345).asIssueFooter()

            assertTrue(footer, footer.contains("12,345"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
