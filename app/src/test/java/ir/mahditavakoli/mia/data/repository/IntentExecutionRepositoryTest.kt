package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.ActionType
import ir.mahditavakoli.mia.data.model.VoiceCommandIntent
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.GitHubUser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The three task-state actions end to end: what they write to Supabase, what they mirror onto
 * GitHub, and what they say when the task the user named does not exist.
 */
class IntentExecutionRepositoryTest {

    private val base64 = Base64Encoder { Base64.getEncoder().encodeToString(it) }
    private val decoder = Base64Decoder { Base64.getDecoder().decode(it) }

    private fun gitHub(api: FakeGitHubApi, configured: Boolean = true) = GitHubRepository(
        api = api,
        isConfigured = configured,
        bootstrapper = RepoBootstrapper(
            api = api,
            base64 = base64,
            encryptor = SecretEncryptor { plaintext, _ -> plaintext },
            files = { emptyList() }
        ),
        agentModelMigrator = AgentModelMigrator(api, base64, decoder),
        agentApiKeyProvider = { null },
        agentFallbackApiKeyProvider = { null }
    )

    private fun issue(number: Int, title: String, state: String = "open") = GitHubIssueDetail(
        number = number,
        title = title,
        body = null,
        state = state,
        user = GitHubUser("octocat"),
        createdAt = "2026-01-01T00:00:00Z",
        htmlUrl = "https://github.com/octocat/website/issues/$number"
    )

    /** A project named in Latin so [GitHubRepository.repoNameFor] gives a readable repo slug. */
    private fun fixture(gitHubConfigured: Boolean = true): Triple<FakeSupabaseApi, FakeGitHubApi, IntentExecutionRepository> {
        val supabase = FakeSupabaseApi()
        supabase.addProject("p1", "website")
        supabase.addTask("t1", "p1", "طراحی لوگو", dueDate = "2026-01-20")
        val gitHubApi = FakeGitHubApi()
        return Triple(
            supabase,
            gitHubApi,
            IntentExecutionRepository(supabase, gitHub(gitHubApi, gitHubConfigured))
        )
    }

    private fun intent(action: ActionType, title: String? = "طراحی لوگو", dueDate: String? = null) =
        VoiceCommandIntent(
            actionType = action,
            projectName = "website",
            taskTitle = title,
            dueDate = dueDate
        )

    @Test
    fun `complete_task marks the task done and closes its issue`() = runBlocking {
        val (supabase, gitHubApi, repository) = fixture()
        gitHubApi.issues += issue(7, "طراحی لوگو")

        val message = repository.execute(intent(ActionType.COMPLETE_TASK), agentHandled = false)
            .getOrThrow()

        assertTrue(supabase.task("t1").isDone)
        // The due date is untouched: closing a task says nothing about its deadline.
        assertEquals("2026-01-20", supabase.task("t1").dueDate)
        assertEquals(listOf(7 to "closed"), gitHubApi.issueStateUpdates)
        assertTrue(message, message.contains("#7"))
    }

    @Test
    fun `reopen_task clears is_done and reopens its issue`() = runBlocking {
        val (supabase, gitHubApi, repository) = fixture()
        supabase.tasks[0] = supabase.task("t1").copy(isDone = true)
        gitHubApi.issues += issue(7, "طراحی لوگو", state = "closed")

        repository.execute(intent(ActionType.REOPEN_TASK), agentHandled = false).getOrThrow()

        assertFalse(supabase.task("t1").isDone)
        assertEquals(listOf(7 to "open"), gitHubApi.issueStateUpdates)
    }

    @Test
    fun `set_due_date moves the deadline without touching is_done`() = runBlocking {
        val (supabase, gitHubApi, repository) = fixture()

        repository.execute(
            intent(ActionType.SET_DUE_DATE, dueDate = "2026-03-06"),
            agentHandled = false
        ).getOrThrow()

        assertEquals("2026-03-06", supabase.task("t1").dueDate)
        assertFalse(supabase.task("t1").isDone)
        // A deadline is MIA's own bookkeeping — there is no issue state to mirror it onto.
        assertTrue(gitHubApi.issueStateUpdates.isEmpty())
    }

    @Test
    fun `a GitHub failure never fails the Supabase write`() = runBlocking {
        val (supabase, gitHubApi, repository) = fixture()
        gitHubApi.issues += issue(7, "طراحی لوگو")
        gitHubApi.updateIssueResponse = { FakeGitHubApi.error(403) }

        val result = repository.execute(intent(ActionType.COMPLETE_TASK), agentHandled = false)

        assertTrue("the action must still succeed", result.isSuccess)
        assertTrue("the task must still be closed", supabase.task("t1").isDone)
        assertTrue(result.getOrThrow(), result.getOrThrow().contains("ناموفق"))
    }

    @Test
    fun `an issue whose title differs only by Persian script variants still matches`() = runBlocking {
        val (_, gitHubApi, repository) = fixture()
        // Arabic Yeh and a نیم‌فاصله where the task title has a plain space.
        gitHubApi.issues += issue(9, "طراحي‌لوگو")

        repository.execute(intent(ActionType.COMPLETE_TASK), agentHandled = false).getOrThrow()

        assertEquals(listOf(9 to "closed"), gitHubApi.issueStateUpdates)
    }

    @Test
    fun `a task with no issue is closed silently`() = runBlocking {
        val (supabase, gitHubApi, repository) = fixture()

        val message = repository.execute(intent(ActionType.COMPLETE_TASK), agentHandled = false)
            .getOrThrow()

        assertTrue(supabase.task("t1").isDone)
        assertTrue(gitHubApi.issueStateUpdates.isEmpty())
        assertFalse(message, message.contains("#"))
    }

    @Test
    fun `every task action reports the task it could not find`() = runBlocking {
        val (supabase, _, repository) = fixture()

        for (action in listOf(ActionType.COMPLETE_TASK, ActionType.REOPEN_TASK, ActionType.SET_DUE_DATE)) {
            val result = repository.execute(
                intent(action, title = "تسک ناموجود", dueDate = "2026-03-06"),
                agentHandled = false
            )
            assertTrue("$action should fail", result.isFailure)
            assertTrue(
                "$action: ${result.exceptionOrNull()?.message}",
                result.exceptionOrNull()?.message?.contains("تسک ناموجود") == true
            )
        }
        // Nothing was written for any of them.
        assertTrue(supabase.taskUpdates.isEmpty())
    }

    @Test
    fun `set_due_date without a date is refused rather than clearing the column`() = runBlocking {
        val (supabase, _, repository) = fixture()

        val result = repository.execute(intent(ActionType.SET_DUE_DATE), agentHandled = false)

        assertTrue(result.isFailure)
        assertEquals("2026-01-20", supabase.task("t1").dueDate)
    }
}
