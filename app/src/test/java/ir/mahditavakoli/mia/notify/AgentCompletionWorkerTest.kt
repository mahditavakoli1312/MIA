package ir.mahditavakoli.mia.notify

import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.notify.AgentCompletionWorker.Companion.isFinished
import ir.mahditavakoli.mia.notify.AgentCompletionWorker.Companion.nowIso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * The rule that decides whether a notification is posted. It is the one piece of the worker that
 * is pure logic, and the one that must never be wrong in the "announce failed work as done"
 * direction.
 */
class AgentCompletionWorkerTest {

    private fun issue(isOpen: Boolean, vararg labels: String) = RepoIssue(
        number = 1,
        title = "طراحی لوگو",
        body = null,
        isOpen = isOpen,
        author = "octocat",
        createdAt = "2026-01-01T00:00:00Z",
        commentCount = 0,
        labels = labels.toList(),
        htmlUrl = "https://github.com/octocat/website/issues/1"
    )

    @Test
    fun `the done label marks work finished even while the issue is still open`() {
        assertTrue(issue(isOpen = true, "done").isFinished)
    }

    @Test
    fun `an issue closed by a merged PR is finished with no label at all`() {
        // GitHub closes the issue itself when the PR body says "Closes #n", so there is nothing
        // but the state to read.
        assertTrue(issue(isOpen = false).isFinished)
    }

    @Test
    fun `an agent-failed issue is never announced as work done`() {
        assertFalse(issue(isOpen = false, "agent-failed").isFinished)
        assertFalse(issue(isOpen = false, "done", "agent-failed").isFinished)
    }

    @Test
    fun `work still in the queue is not finished`() {
        assertFalse(issue(isOpen = true, "by-agent").isFinished)
        assertFalse(issue(isOpen = true, "agent-running").isFinished)
    }

    @Test
    fun `the watermark is formatted the way GitHub's since parameter expects`() {
        // Epoch, so the assertion doesn't depend on the machine's clock — but it does prove the
        // formatter is pinned to UTC rather than the default zone.
        assertEquals("1970-01-01T00:00:00Z", nowIso(Date(0)))
    }
}
