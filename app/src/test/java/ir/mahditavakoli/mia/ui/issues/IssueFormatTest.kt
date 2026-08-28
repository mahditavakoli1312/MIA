package ir.mahditavakoli.mia.ui.issues

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueFormatTest {

    @Test
    fun `a github timestamp keeps its date and minute`() {
        assertEquals("2026-08-28 09:15", formatIssueTimestamp("2026-08-28T09:15:00Z"))
    }

    @Test
    fun `anything not shaped like a timestamp is passed through`() {
        assertEquals("", formatIssueTimestamp(null))
        assertEquals("", formatIssueTimestamp("   "))
        assertEquals("2026-08-28", formatIssueTimestamp("2026-08-28"))
        assertEquals("soon", formatIssueTimestamp("soon"))
    }
}
