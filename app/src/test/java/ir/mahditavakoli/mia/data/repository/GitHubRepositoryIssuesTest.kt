package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.GitHubLabel
import ir.mahditavakoli.mia.network.github.GitHubUser
import ir.mahditavakoli.mia.network.github.PullRequestRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `re-do comments the agent trigger with the issue body as the brief`() = runBlocking {
        val api = FakeGitHubApi()
        api.issues += issue(5)
        val repository = repository(api)
        val target = repository.issueFor("My Project", 5).getOrThrow()

        val posted = repository.redoIssue("My Project", target).getOrThrow()

        assertEquals("@tec do this : body of 5", posted.body)
        assertEquals(listOf("@tec do this : body of 5"), api.issueComments[5]?.map { it.body })
    }

    @Test
    fun `re-do falls back to the title and drops the spend footer`() {
        val withFooter = RepoIssue(
            number = 5,
            title = "Add a logout button",
            body = "Put it in the drawer.\n---\nMIA spend: 1,204 tokens",
            isOpen = true,
            author = "octocat",
            createdAt = null,
            commentCount = 0,
            labels = emptyList(),
            htmlUrl = ""
        )
        assertEquals(
            "@tec do this : Put it in the drawer.",
            GitHubRepository.redoCommentFor(withFooter)
        )
        // An issue with no body at all still has to tell the agent what to build.
        assertEquals(
            "@tec do this : Add a logout button",
            GitHubRepository.redoCommentFor(withFooter.copy(body = "   "))
        )
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
    fun `a new issue is opened with the labels the user picked`() = runBlocking {
        val api = FakeGitHubApi()

        val created = repository(api).createIssue(
            projectName = "My Project",
            title = "  Add a login screen  ",
            body = "  with Google sign-in  ",
            labels = listOf(GitHubRepository.AGENT_LABEL, "bug")
        ).getOrThrow()

        val sent = api.createdIssues.single()
        // Whitespace the user left around the fields never reaches GitHub.
        assertEquals("Add a login screen", sent.title)
        assertEquals("with Google sign-in", sent.body)
        assertEquals(listOf("by-agent", "bug"), sent.labels)
        assertTrue("a new issue is open", created.isOpen)
        assertEquals(listOf("by-agent", "bug"), created.labels)
    }

    @Test
    fun `an issue with no labels and no body sends neither`() = runBlocking {
        val api = FakeGitHubApi()

        repository(api).createIssue("My Project", "Just a title", body = "   ", labels = emptyList())

        val sent = api.createdIssues.single()
        assertNull("an empty body must not be sent as an empty string", sent.body)
        assertNull("no labels means the field is omitted", sent.labels)
    }

    @Test
    fun `a blank title is rejected before it reaches GitHub`() = runBlocking {
        val api = FakeGitHubApi()

        val result = repository(api).createIssue("My Project", "   ", body = "x", labels = emptyList())

        assertTrue(result.isFailure)
        assertTrue("nothing should have been opened", api.createdIssues.isEmpty())
    }

    @Test
    fun `the agent label is always offered even when the repo does not define it`() = runBlocking {
        val api = FakeGitHubApi()
        api.labels += listOf(GitHubLabel("bug"), GitHubLabel("done"))

        val offered = repository(api).labelsFor("My Project").getOrThrow()

        assertEquals(listOf("by-agent", "bug", "done"), offered)
    }

    @Test
    fun `a repo that already defines the agent label is not given a second copy`() = runBlocking {
        val api = FakeGitHubApi()
        api.labels += listOf(GitHubLabel("by-agent"), GitHubLabel("done"))

        val offered = repository(api).labelsFor("My Project").getOrThrow()

        assertEquals(listOf("by-agent", "done"), offered)
    }

    @Test
    fun `an agent-handled task carries the label that fires the workflow`() = runBlocking {
        val api = FakeGitHubApi()

        repository(api).createIssueForTask(
            projectName = "My Project",
            taskTitle = "Ship it",
            description = "the brief",
            dueDate = "2026-09-01",
            agentHandled = true
        ).getOrThrow()

        val sent = api.createdIssues.single()
        assertEquals(listOf("by-agent"), sent.labels)
        assertTrue("the due date belongs in the body", sent.body.orEmpty().contains("2026-09-01"))
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
