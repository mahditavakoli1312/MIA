package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the GitHub→Supabase sync lives by: match by folded title, and only ever close.
 */
class IssueTaskSyncTest {

    private fun task(id: String, title: String, isDone: Boolean = false) =
        Task(id = id, projectId = "p1", title = title, isDone = isDone)

    private fun issue(number: Int, title: String, isOpen: Boolean) = RepoIssue(
        number = number,
        title = title,
        body = null,
        isOpen = isOpen,
        author = "octocat",
        createdAt = "2026-01-01T00:00:00Z",
        commentCount = 0,
        labels = emptyList(),
        htmlUrl = "https://github.com/octocat/website/issues/$number"
    )

    @Test
    fun `a closed issue closes the task with the same title`() {
        val ids = IssueTaskSync.tasksToClose(
            tasks = listOf(task("t1", "طراحی لوگو"), task("t2", "صفحه ورود")),
            issues = listOf(issue(1, "طراحی لوگو", isOpen = false), issue(2, "صفحه ورود", isOpen = true))
        )

        assertEquals(setOf("t1"), ids)
    }

    @Test
    fun `titles are matched on their folded form`() {
        val ids = IssueTaskSync.tasksToClose(
            tasks = listOf(task("t1", "برنامه‌ریزی محتوا")),
            // Arabic Yeh, a plain space instead of the ZWNJ, and Arabic-Indic digits nowhere near
            // the point — all of which PersianText.fold collapses.
            issues = listOf(issue(1, "برنامه ريزي محتوا", isOpen = false))
        )

        assertEquals(setOf("t1"), ids)
    }

    @Test
    fun `an open issue never reopens a task the user closed in the app`() {
        val ids = IssueTaskSync.tasksToClose(
            tasks = listOf(task("t1", "طراحی لوگو", isDone = true)),
            issues = listOf(issue(1, "طراحی لوگو", isOpen = true))
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `a task already done is not written again`() {
        val ids = IssueTaskSync.tasksToClose(
            tasks = listOf(task("t1", "طراحی لوگو", isDone = true)),
            issues = listOf(issue(1, "طراحی لوگو", isOpen = false))
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `a closed issue with no matching task changes nothing`() {
        val ids = IssueTaskSync.tasksToClose(
            tasks = listOf(task("t1", "طراحی لوگو")),
            issues = listOf(issue(1, "چیز دیگری", isOpen = false))
        )

        assertTrue(ids.isEmpty())
    }

    @Test
    fun `every closed task goes out in one batched PATCH`() = runBlocking {
        val api = FakeSupabaseApi()
        api.addProject("p1", "website")
        val project = Project(
            id = "p1",
            name = "website",
            tasks = listOf(task("t1", "طراحی لوگو"), task("t2", "صفحه ورود"), task("t3", "باز می‌ماند"))
        )

        val closed = IssueTaskSync(api).closeTasksForClosedIssues(
            project = project,
            issues = listOf(
                issue(1, "طراحی لوگو", isOpen = false),
                issue(2, "صفحه ورود", isOpen = false),
                issue(3, "باز می‌ماند", isOpen = true)
            )
        ).getOrThrow()

        assertEquals(setOf("t1", "t2"), closed)
        assertEquals(1, api.taskUpdates.size)
        val (filter, body) = api.taskUpdates.single()
        assertTrue(filter, filter.startsWith("in.("))
        assertTrue(filter, filter.contains("t1") && filter.contains("t2"))
        assertEquals(true, body.isDone)
        // Nothing about the deadline is being asserted by a state sync.
        assertEquals(null, body.dueDate)
    }

    @Test
    fun `nothing to close spends no request at all`() = runBlocking {
        val api = FakeSupabaseApi()
        val project = Project(id = "p1", name = "website", tasks = listOf(task("t1", "طراحی لوگو")))

        val closed = IssueTaskSync(api)
            .closeTasksForClosedIssues(project, listOf(issue(1, "طراحی لوگو", isOpen = true)))
            .getOrThrow()

        assertTrue(closed.isEmpty())
        assertTrue(api.taskUpdates.isEmpty())
    }
}
