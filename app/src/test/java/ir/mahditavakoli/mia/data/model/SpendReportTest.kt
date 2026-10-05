package ir.mahditavakoli.mia.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The spend screen is a set of derived views over one flat list of entries, so the arithmetic —
 * not the composables — is what can be wrong. The rules that matter here are the honest ones: an
 * unreported cost is never turned into $0.00, and a week with nothing in it keeps its place in the
 * chart instead of being dropped.
 */
class SpendReportTest {

    /** 2025-09-08 is a Monday, so its week starts on Saturday 2025-09-06. */
    private val monday = 1_757_289_600_000L // 2025-09-08T00:00:00Z

    private fun entry(
        role: SpendRole = SpendRole.TEC,
        model: String = "m3:free",
        tokens: Int = 1_000,
        costUsd: Double? = null,
        project: String? = "بازار",
        issue: Int? = null,
        at: Long = monday
    ) = SpendEntry(
        role = role,
        model = model,
        tokens = tokens,
        costUsd = costUsd,
        projectName = project,
        issueNumber = issue,
        atMillis = at
    )

    // --- totals -----------------------------------------------------------------------------

    @Test
    fun `an unreported cost counts as nothing, not as a free run`() {
        val report = SpendReport(
            entries = listOf(
                entry(tokens = 100, costUsd = null),
                entry(tokens = 200, costUsd = 0.5)
            )
        )

        assertEquals(300, report.totalTokens)
        // The 100-token entry contributes no money because nobody said what it cost.
        assertEquals(0.5, report.totalCostUsd, 1e-9)
    }

    @Test
    fun `an empty report knows it is empty`() {
        assertTrue(SpendReport().isEmpty)
        assertEquals(0, SpendReport().totalTokens)
    }

    // --- breakdowns -------------------------------------------------------------------------

    @Test
    fun `role breakdown drops roles that spent nothing and sorts by tokens`() {
        val report = SpendReport(
            entries = listOf(
                entry(role = SpendRole.MIA, tokens = 500),
                entry(role = SpendRole.TEC, tokens = 4_000),
                entry(role = SpendRole.TEC, tokens = 1_000)
            )
        )

        val slices = report.byRole
        assertEquals(listOf(SpendRole.TEC.persianLabel, SpendRole.MIA.persianLabel), slices.map { it.label })
        assertEquals(5_000, slices[0].tokens)
        assertEquals(2, slices[0].calls)
        // PO and QC never ran, so they are absent rather than shown as zero rows.
        assertEquals(2, slices.size)
    }

    @Test
    fun `model breakdown groups by model id and names a blank one`() {
        val report = SpendReport(
            entries = listOf(
                entry(model = "m3:free", tokens = 100),
                entry(model = "m3:free", tokens = 300),
                entry(model = "", tokens = 50)
            )
        )

        val slices = report.byModel
        assertEquals("m3:free", slices[0].label)
        assertEquals(400, slices[0].tokens)
        assertEquals("نامشخص", slices[1].label)
    }

    // --- most expensive issues --------------------------------------------------------------

    @Test
    fun `top issues sums every actor on one issue and keeps only five`() {
        val entries = (1..7).map { n -> entry(tokens = n * 1_000, issue = n) } +
            // A second actor on issue 7: the screen shows what an issue cost in total, not what
            // one role spent on it.
            entry(role = SpendRole.QC, tokens = 500, issue = 7)
        val report = SpendReport(entries = entries)

        val top = report.topIssues
        assertEquals(5, top.size)
        assertEquals(7, top[0].issueNumber)
        assertEquals(7_500, top[0].tokens)
        assertEquals(6, top[1].issueNumber)
    }

    @Test
    fun `spend not tied to an issue stays out of the issue ranking`() {
        val report = SpendReport(
            entries = listOf(
                entry(role = SpendRole.MIA, tokens = 9_999, project = null, issue = null),
                entry(tokens = 10, issue = 3)
            )
        )

        assertEquals(listOf(3), report.topIssues.map { it.issueNumber })
        // …but it is still part of the total, because it was still spent.
        assertEquals(10_009, report.totalTokens)
    }

    /** The same issue number in two different projects is two different issues. */
    @Test
    fun `issues are keyed by project as well as number`() {
        val report = SpendReport(
            entries = listOf(
                entry(project = "بازار", issue = 1, tokens = 100),
                entry(project = "تقویم", issue = 1, tokens = 200)
            )
        )

        assertEquals(2, report.topIssues.size)
    }

    // --- the weekly chart -------------------------------------------------------------------

    @Test
    fun `weeks start on saturday`() {
        val saturday = 1_757_116_800_000L // 2025-09-06T00:00:00Z
        assertEquals(saturday, SpendReport.weekStart(monday))
        assertEquals(saturday, SpendReport.weekStart(saturday))
        // One millisecond earlier belongs to the week before.
        assertEquals(saturday - 7 * 86_400_000L, SpendReport.weekStart(saturday - 1))
    }

    /**
     * A chart that dropped its empty weeks would put two bars a month apart side by side, which
     * reads as continuous work.
     */
    @Test
    fun `empty weeks keep their place in the chart`() {
        val report = SpendReport(
            entries = listOf(
                entry(tokens = 100, at = monday),
                entry(tokens = 50, at = monday - 21 * 86_400_000L)
            )
        )

        val weeks = report.weekly(now = monday, weeks = 8)
        assertEquals(8, weeks.size)
        // Oldest first, so this week is the last bar.
        assertEquals(100, weeks.last().tokens)
        assertEquals(50, weeks[weeks.size - 4].tokens)
        assertEquals(0, weeks[weeks.size - 2].tokens)
        assertTrue(weeks.zipWithNext().all { (a, b) -> a.startMillis < b.startMillis })
    }

    @Test
    fun `an entry with no timestamp is left out of the chart but not the total`() {
        val report = SpendReport(entries = listOf(entry(tokens = 400, at = 0L)))

        assertEquals(0, report.weekly(now = monday).sumOf { it.tokens })
        assertEquals(400, report.totalTokens)
    }

    // --- the budget bar ---------------------------------------------------------------------

    @Test
    fun `no budget means no bar`() {
        assertNull(SpendReport(entries = listOf(entry())).budgetFraction(0, monday))
    }

    @Test
    fun `the budget only counts the last thirty days`() {
        val report = SpendReport(
            entries = listOf(
                entry(tokens = 400, at = monday),
                entry(tokens = 600, at = monday - 40 * 86_400_000L)
            )
        )

        assertEquals(400, report.tokensThisMonth(monday))
        assertEquals(0.4f, report.budgetFraction(1_000, monday)!!, 1e-6f)
    }

    /** Past 100% the fraction keeps growing: "just over" and "three times over" differ. */
    @Test
    fun `a blown budget is not clamped`() {
        val report = SpendReport(entries = listOf(entry(tokens = 3_000, at = monday)))

        assertEquals(3f, report.budgetFraction(1_000, monday)!!, 1e-6f)
    }

    // --- formatting -------------------------------------------------------------------------

    @Test
    fun `numbers group with commas whatever the device locale is`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            assertEquals("1,234,567", SpendReport.groupTokens(1_234_567))
            assertEquals("$0.0123", SpendReport.formatCost(0.0123))
        } finally {
            Locale.setDefault(original)
        }
    }
}
