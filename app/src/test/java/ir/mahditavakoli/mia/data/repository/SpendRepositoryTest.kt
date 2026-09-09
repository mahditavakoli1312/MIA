package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.SpendEntry
import ir.mahditavakoli.mia.data.model.SpendRole
import ir.mahditavakoli.mia.network.github.GitHubCommit
import ir.mahditavakoli.mia.network.github.GitHubCommitAuthor
import ir.mahditavakoli.mia.network.github.GitHubCommitDetail
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The screen this backs is only worth having if its numbers are honest, and the two ways it could
 * lie are both here: presenting a paged floor as a total, and letting one unreadable repo take the
 * other projects' numbers down with it.
 */
class SpendRepositoryTest {

    private val base64 = Base64Encoder { Base64.getEncoder().encodeToString(it) }
    private val decoder = Base64Decoder { Base64.getDecoder().decode(it) }

    private fun repository(
        api: FakeGitHubApi,
        isConfigured: Boolean = true,
        local: List<SpendEntry> = emptyList()
    ) = SpendRepository(
        api = api,
        gitHub = GitHubRepository(
            api = api,
            isConfigured = isConfigured,
            bootstrapper = RepoBootstrapper(
                api = api,
                base64 = base64,
                encryptor = SecretEncryptor { plaintext, _ -> plaintext },
                files = emptyList()
            ),
            agentModelMigrator = AgentModelMigrator(api, base64, decoder),
            agentApiKeyProvider = { null },
            agentFallbackApiKeyProvider = { null }
        ),
        localEntries = { local }
    )

    private fun commit(message: String, date: String = "2026-09-01T10:00:00Z") = GitHubCommit(
        sha = message.hashCode().toString(),
        commit = GitHubCommitDetail(message = message, author = GitHubCommitAuthor(date)),
        htmlUrl = "https://github.com/octocat/bazaar/commit/abc"
    )

    private fun comment(id: Long, body: String) = GitHubIssueComment(
        id = id,
        body = body,
        user = GitHubUser("octocat"),
        createdAt = "2026-09-01T11:00:00Z",
        htmlUrl = "https://github.com/octocat/bazaar/issues/12#issuecomment-$id"
    )

    private val projects = listOf(Project(id = "1", name = "bazaar"))

    @Test
    fun `reads TEC spend off commit trailers and attributes it to the project`() = runBlocking {
        val api = FakeGitHubApi()
        api.commits += commit(
            "tec: resolve #12 — صفحه ورود\n\n" +
                "Token-Spend: 41,083 tokens (\$0.0000) via openrouter/minimax/minimax-m3:free"
        )
        api.commits += commit("Improve issue ordering") // no trailer

        val report = repository(api).report(projects).getOrThrow()

        assertEquals(1, report.entries.size)
        val entry = report.entries.single()
        assertEquals(SpendRole.TEC, entry.role)
        assertEquals(41_083, entry.tokens)
        assertEquals(12, entry.issueNumber)
        assertEquals("bazaar", entry.projectName)
        assertFalse(report.isTruncated)
    }

    @Test
    fun `reads PO and QC spend off the repo-wide comment feed`() = runBlocking {
        val api = FakeGitHubApi()
        api.issueComments[12] = mutableListOf(
            comment(
                1,
                "### 🗺️ Plan\n\n---\n\n🧾 **Spend for this decomposition** — 2,000 tokens · " +
                    "\$0.00 (free model) · `m3:free`"
            ),
            comment(
                2,
                "### ✅ QC verdict\n\n---\n\n🧾 **Spend for this review** — 500 tokens · " +
                    "\$0.00 (free model) · `m3:free`"
            )
        )

        val report = repository(api).report(projects).getOrThrow()

        assertEquals(listOf(SpendRole.PO, SpendRole.QC), report.entries.map { it.role })
        // The issue number comes from the `issue_url` GitHub puts on this endpoint.
        assertTrue(report.entries.all { it.issueNumber == 12 })
        assertEquals(2_500, report.totalTokens)
    }

    /**
     * The whole point of the "at least X" wording on the screen: three full pages means there is
     * very likely a fourth that was never read.
     */
    @Test
    fun `a repo with more history than the page limit reports itself truncated`() = runBlocking {
        val api = FakeGitHubApi()
        repeat(300) { n ->
            api.commits += commit("tec: resolve #$n\n\nToken-Spend: 10 tokens via m3:free")
        }

        val report = repository(api).report(projects).getOrThrow()

        assertTrue(report.isTruncated)
        assertEquals(300, report.entries.size)
    }

    @Test
    fun `a repo that fits inside the page limit is not called truncated`() = runBlocking {
        val api = FakeGitHubApi()
        api.commits += commit("tec: resolve #1\n\nToken-Spend: 10 tokens via m3:free")

        assertFalse(repository(api).report(projects).getOrThrow().isTruncated)
    }

    /** One project without a repo must not hide the numbers for the others. */
    @Test
    fun `an unreadable repo is named rather than failing the whole report`() = runBlocking {
        val api = FakeGitHubApi()
        api.commits += commit("tec: resolve #1\n\nToken-Spend: 700 tokens via m3:free")
        // Every read for the second project's repo throws, which is what a repo that was never
        // created looks like from here.
        api.failCommitsFor = "ghost"
        api.failCommentsFor = "ghost"

        val report = repository(api).report(
            projects + Project(id = "2", name = "ghost")
        ).getOrThrow()

        assertEquals(listOf("ghost"), report.unreadableProjects)
        assertEquals(700, report.totalTokens)
    }

    @Test
    fun `without a GitHub token only the app's own ledger is reported`() = runBlocking {
        val api = FakeGitHubApi()
        api.commits += commit("tec: resolve #1\n\nToken-Spend: 999 tokens via m3:free")
        val local = listOf(
            SpendEntry(role = SpendRole.MIA, model = "gemini-2.5-flash", tokens = 1_444)
        )

        val report = repository(api, isConfigured = false, local = local).report(projects).getOrThrow()

        assertEquals(listOf(SpendRole.MIA), report.entries.map { it.role })
        assertEquals(1_444, report.totalTokens)
    }

    @Test
    fun `the app's own spend is merged with what the agents left on GitHub`() = runBlocking {
        val api = FakeGitHubApi()
        api.commits += commit("tec: resolve #1\n\nToken-Spend: 1,000 tokens via m3:free")
        val local = listOf(
            SpendEntry(role = SpendRole.MIA, model = "gemini-2.5-flash", tokens = 500)
        )

        val report = repository(api, local = local).report(projects).getOrThrow()

        assertEquals(1_500, report.totalTokens)
        assertEquals(2, report.byRole.size)
    }
}
