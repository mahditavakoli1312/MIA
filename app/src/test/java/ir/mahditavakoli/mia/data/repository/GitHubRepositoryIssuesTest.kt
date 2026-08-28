package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.GitHubLabel
import ir.mahditavakoli.mia.network.github.GitHubUser
import ir.mahditavakoli.mia.network.github.PullRequestRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The read side of [GitHubRepository]: what the issues screens and the project cards show. */
class GitHubRepositoryIssuesTest {

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
        state: String = "open",
        title: String = "Task $number",
        labels: List<String> = emptyList(),
        pullRequest: PullRequestRef? = null
    ) = GitHubIssueDetail(
        number = number,
        title = title,
        body = "body of $number",
        state = state,
        user = GitHubUser("octocat"),
        labels = labels.map { GitHubLabel(it) },
        comments = 0,
        createdAt = "2026-08-28T09:15:00Z",
        htmlUrl = "https://github.com/octocat/my-project/issues/$number",
        pullRequest = pullRequest
    )

    @Test
    fun `counts split open from closed and ignore pull requests`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += listOf(
            issue(4, state = "open"),
            issue(3, state = "closed"),
            issue(2, state = "open", pullRequest = PullRequestRef("https://github.com/o/r/pull/2")),
            issue(1, state = "closed")
        )

        val list = repository(api).issuesFor("My Project").getOrThrow()

        // The PR is gone entirely: it is neither an open issue nor a closed one.
        assertEquals(listOf(4, 3, 1), list.issues.map { it.number })
        assertEquals(1, list.counts.open)
        assertEquals(2, list.counts.closed)
        assertFalse(list.isTruncated)
    }

    @Test
    fun `state string becomes isOpen and labels are flattened`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += issue(7, state = "closed", labels = listOf("by-agent", "bug"))

        val loaded = repository(api).issuesFor("My Project").getOrThrow().issues.single()

        assertFalse(loaded.isOpen)
        assertEquals(listOf("by-agent", "bug"), loaded.labels)
        assertEquals("octocat", loaded.author)
        assertEquals("2026-08-28T09:15:00Z", loaded.createdAt)
    }

    @Test
    fun `a repo with more issues than MIA pages through reports itself truncated`() = runBlocking {
        val api = FakeGitHubApi()
        // Three full pages of 100 is exactly the cap, so there may well be more behind it.
        api.issues += (1..300).map { issue(it) }

        val list = repository(api).issuesFor("My Project").getOrThrow()

        assertEquals(300, list.issues.size)
        assertTrue("a full last page means the counts are a floor", list.isTruncated)
    }

    @Test
    fun `a short final page ends paging without claiming truncation`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += (1..150).map { issue(it) }

        val list = repository(api).issuesFor("My Project").getOrThrow()

        assertEquals(150, list.issues.size)
        assertFalse(list.isTruncated)
    }

    @Test
    fun `comments are read in order and a posted one comes back mapped`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += issue(5)
        api.issueComments[5] = mutableListOf(
            GitHubIssueComment(
                id = 1,
                body = "first",
                user = GitHubUser("agent"),
                createdAt = "2026-08-28T10:00:00Z"
            )
        )
        val repository = repository(api)

        val posted = repository.addIssueComment("My Project", 5, "second").getOrThrow()
        assertEquals("second", posted.body)
        assertEquals("octocat", posted.author)

        val thread = repository.issueCommentsFor("My Project", 5).getOrThrow()
        assertEquals(listOf("first", "second"), thread.map { it.body })
    }

    @Test
    fun `a blank comment is rejected before it reaches GitHub`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += issue(5)

        val result = repository(api).addIssueComment("My Project", 5, "   ")

        assertTrue(result.isFailure)
        assertTrue("nothing should have been posted", api.issueComments[5].isNullOrEmpty())
    }

    @Test
    fun `one issue is read by number`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += listOf(issue(8, title = "Wire up login"), issue(9))

        val loaded = repository(api).issueFor("My Project", 8).getOrThrow()

        assertEquals(8, loaded.number)
        assertEquals("Wire up login", loaded.title)
        assertEquals("body of 8", loaded.body)
    }
}
