package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.BriefStatus
import ir.mahditavakoli.mia.data.model.IssueLabels
import ir.mahditavakoli.mia.data.model.IssueList
import ir.mahditavakoli.mia.data.model.RepoIssue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The brief stage: filing one long-form intent, and counting briefs apart from ordinary issues.
 *
 * The counting rules are the ones the project card and the issues tabs both read, so a change
 * that mixed briefs back into the open count would show up here rather than as a number the user
 * has to distrust.
 */
class BriefTest {

    private val base64 = Base64Encoder { Base64.getEncoder().encodeToString(it) }
    private val decoder = Base64Decoder { Base64.getDecoder().decode(it) }

    private fun repository(api: FakeGitHubApi) = GitHubRepository(
        api = api,
        isConfigured = true,
        bootstrapper = RepoBootstrapper(
            api = api,
            base64 = base64,
            encryptor = SecretEncryptor { plaintext, _ -> plaintext },
            files = emptyList()
        ),
        agentModelMigrator = AgentModelMigrator(api, base64, decoder),
        agentApiKeyProvider = { null },
        agentFallbackApiKeyProvider = { null }
    )

    private fun issue(
        number: Int,
        isOpen: Boolean = true,
        labels: List<String> = emptyList()
    ) = RepoIssue(
        number = number,
        title = "issue $number",
        body = null,
        isOpen = isOpen,
        author = "octocat",
        createdAt = "2026-01-01T00:00:00Z",
        commentCount = 0,
        labels = labels,
        htmlUrl = "https://github.com/octocat/p/issues/$number"
    )

    @Test
    fun `a brief is labelled brief and nothing else`() = runBlocking {
        val api = FakeGitHubApi()
        val issue = repository(api).createBrief(
            projectName = "پروژهٔ من",
            title = "گزارش هزینه",
            description = "می‌خواهم اپ هزینهٔ هفتگی من را نشان بدهد و روند آن را مقایسه کند."
        ).getOrThrow()

        assertEquals(listOf(IssueLabels.BRIEF), api.createdIssues.single().labels)
        // Never by-agent: handing a week of work to TEC is the failure this stage prevents.
        assertFalse(IssueLabels.AGENT in issue.labels)
        assertTrue(issue.isBrief)
        assertEquals(BriefStatus.PENDING, issue.briefStatus)
    }

    @Test
    fun `the success criterion becomes its own section, and is left out when absent`() = runBlocking {
        val api = FakeGitHubApi()
        val repository = repository(api)

        repository.createBrief(
            projectName = "پروژه",
            title = "گزارش",
            description = "شرح کامل نیت که به‌قدر کافی بلند است.",
            successCriteria = "کاربر جمع هفته را در یک نگاه ببیند."
        ).getOrThrow()
        val withCriteria = api.createdIssues.last().body.orEmpty()
        assertTrue(withCriteria.startsWith("شرح کامل نیت"))
        assertTrue(withCriteria.contains("## معیار موفقیت"))
        assertTrue(withCriteria.trimEnd().endsWith("کاربر جمع هفته را در یک نگاه ببیند."))

        repository.createBrief(
            projectName = "پروژه",
            title = "گزارش",
            description = "شرح کامل نیت.",
            successCriteria = "   "
        ).getOrThrow()
        assertFalse(api.createdIssues.last().body.orEmpty().contains("## معیار موفقیت"))
    }

    @Test
    fun `an empty title or description is rejected before any request`() = runBlocking {
        val api = FakeGitHubApi()
        val repository = repository(api)

        assertTrue(repository.createBrief("پروژه", "  ", "شرح").isFailure)
        assertTrue(repository.createBrief("پروژه", "عنوان", "   ").isFailure)
        assertTrue(api.createdIssues.isEmpty())
    }

    @Test
    fun `open and closed counts exclude briefs, which are counted on their own`() {
        val list = IssueList(
            issues = listOf(
                issue(1, isOpen = true),
                issue(2, isOpen = false),
                issue(3, isOpen = true, labels = listOf(IssueLabels.BRIEF)),
                issue(4, isOpen = true, labels = listOf(IssueLabels.BRIEF, IssueLabels.BRIEF_PLANNED)),
                issue(5, isOpen = true, labels = listOf(IssueLabels.BRIEF, IssueLabels.BRIEF_FAILED)),
                // A closed brief is history: counted in neither, the same as a decomposed one.
                issue(6, isOpen = false, labels = listOf(IssueLabels.BRIEF))
            )
        )

        val counts = list.counts
        assertEquals(1, counts.open)
        assertEquals(1, counts.closed)
        assertEquals(3, counts.briefs.total)
        assertEquals(1, counts.briefs.pending)
        assertEquals(1, counts.briefs.failed)
        assertEquals(listOf(3, 4, 5, 6), list.briefs.map { it.number })
        assertEquals(listOf(1, 2), list.ordinary.map { it.number })
    }

    @Test
    fun `brief status comes from the labels the workflow maintains`() {
        assertEquals(
            BriefStatus.PENDING,
            issue(1, labels = listOf(IssueLabels.BRIEF)).briefStatus
        )
        assertEquals(
            BriefStatus.PLANNED,
            issue(1, labels = listOf(IssueLabels.BRIEF, IssueLabels.BRIEF_PLANNED)).briefStatus
        )
        assertEquals(
            BriefStatus.FAILED,
            issue(1, labels = listOf(IssueLabels.BRIEF, IssueLabels.BRIEF_FAILED)).briefStatus
        )
        // A brief the PO re-planned after a failure reads as planned: the newer outcome wins.
        assertEquals(
            BriefStatus.PLANNED,
            issue(
                1,
                labels = listOf(IssueLabels.BRIEF, IssueLabels.BRIEF_FAILED, IssueLabels.BRIEF_PLANNED)
            ).briefStatus
        )
    }
}
