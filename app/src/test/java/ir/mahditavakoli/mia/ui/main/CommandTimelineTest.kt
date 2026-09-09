package ir.mahditavakoli.mia.ui.main

import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.IssueLabels
import ir.mahditavakoli.mia.data.model.RepoIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub half of the timeline, which is entirely derived from an issue's labels and comments.
 *
 * These tests are the contract between this app and the TEC workflow's comment wording: if the
 * workflow ever stops saying "picked this up from the queue" or "squash-merged", a row here goes
 * grey and the user is told nothing is happening while an agent works. That is why the strings are
 * asserted rather than trusted.
 */
class CommandTimelineTest {

    private val tracked = TrackedIssue(
        projectName = "پروژهٔ من",
        number = 7,
        title = "دکمهٔ خروج",
        htmlUrl = "https://github.com/octocat/p/issues/7"
    )

    private fun issue(labels: List<String>) = RepoIssue(
        number = 7,
        title = "دکمهٔ خروج",
        body = null,
        isOpen = IssueLabels.DONE !in labels,
        author = "octocat",
        createdAt = "2026-01-01T00:00:00Z",
        commentCount = 0,
        labels = labels,
        htmlUrl = tracked.htmlUrl
    )

    private fun comment(body: String) = IssueComment(id = 1, author = "bot", body = body, createdAt = null)

    /** A timeline that has finished the app's half and is now watching GitHub. */
    private fun watching(otherIssues: Int = 0) =
        CommandTimeline.starting(now = 0, refining = true)
            .handedOff(tracked, otherIssues = otherIssues, now = 100)

    private fun state(timeline: CommandTimeline, stage: CommandStage) =
        timeline.steps.first { it.stage == stage }.state

    @Test
    fun `a spoken command has no prompt-refinement step`() {
        val spoken = CommandTimeline.starting(now = 0, refining = false)
        assertFalse(spoken.steps.any { it.stage == CommandStage.REFINING })
        assertEquals(CommandStage.UNDERSTANDING, spoken.steps.first().stage)
        assertEquals(StepState.RUNNING, spoken.steps.first().state)
    }

    @Test
    fun `a command that opened no issue ends at execution, collapsed`() {
        val timeline = CommandTimeline.starting(now = 0, refining = true)
            .handedOff(issue = null, otherIssues = 0, now = 100)

        assertTrue(timeline.isFinished)
        assertFalse(timeline.isExpanded)
        assertNull(timeline.issue)
        assertTrue(timeline.steps.none { it.stage.isRemote })
        assertTrue(timeline.steps.all { it.state == StepState.DONE })
    }

    @Test
    fun `handing off names the other issues the same command opened`() {
        val step = watching(otherIssues = 2).steps.first { it.stage == CommandStage.ISSUE_CREATED }
        assertTrue(step.note!!.contains("2"))
        assertEquals(tracked.htmlUrl, step.artefactUrl)
    }

    @Test
    fun `a queued issue is waiting, and nothing after it has started`() {
        val timeline = watching().advance(issue(listOf(IssueLabels.AGENT)), emptyList(), now = 200)

        assertEquals(StepState.DONE, state(timeline, CommandStage.ISSUE_CREATED))
        assertEquals(StepState.RUNNING, state(timeline, CommandStage.QUEUED))
        assertEquals(StepState.PENDING, state(timeline, CommandStage.AGENT_RUNNING))
        assertEquals(StepState.PENDING, state(timeline, CommandStage.MERGED))
        assertFalse(timeline.isFinished)
    }

    @Test
    fun `agent-running moves the queue on and starts the build`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.AGENT_RUNNING)),
            listOf(comment("🛠️ **TEC** picked this up from the queue — implementing the change.")),
            now = 200
        )

        assertEquals(StepState.DONE, state(timeline, CommandStage.QUEUED))
        assertEquals(StepState.RUNNING, state(timeline, CommandStage.AGENT_RUNNING))
        assertEquals(StepState.RUNNING, state(timeline, CommandStage.BUILD))
        assertEquals(StepState.PENDING, state(timeline, CommandStage.PR_OPENED))
    }

    @Test
    fun `a pull request proves the build went green`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.AGENT_RUNNING)),
            listOf(comment("🔀 TEC opened https://github.com/octocat/p/pull/12 but couldn't auto-merge it.")),
            now = 300
        )

        assertEquals(StepState.DONE, state(timeline, CommandStage.BUILD))
        assertEquals(StepState.DONE, state(timeline, CommandStage.PR_OPENED))
        assertEquals(
            "https://github.com/octocat/p/pull/12",
            timeline.steps.first { it.stage == CommandStage.PR_OPENED }.artefactUrl
        )
        // Still open: an unmerged PR is not the end of the story.
        assertEquals(StepState.PENDING, state(timeline, CommandStage.MERGED))
        assertFalse(timeline.isFinished)
    }

    @Test
    fun `a merged issue finishes the timeline and every step before it`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.DONE)),
            listOf(comment("✅ TEC implemented this and squash-merged https://github.com/octocat/p/pull/12 into `main`.")),
            now = 400
        )

        assertTrue(timeline.isFinished)
        assertFalse(timeline.hasFailed)
        assertTrue(timeline.steps.all { it.state == StepState.DONE })
        assertTrue(timeline.summary.contains("#7"))
    }

    @Test
    fun `a build that never went green fails the build row and nothing before it`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.AGENT_FAILED)),
            listOf(
                comment("🛠️ **TEC** picked this up from the queue"),
                comment("🛑 TEC produced changes but `./gradlew assembleDebug` still fails after 3 build attempt(s).")
            ),
            now = 400
        )

        assertEquals(StepState.DONE, state(timeline, CommandStage.AGENT_RUNNING))
        assertEquals(StepState.FAILED, state(timeline, CommandStage.BUILD))
        assertEquals(StepState.FAILED, state(timeline, CommandStage.MERGED))
        assertTrue(timeline.isFinished)
        assertTrue(timeline.hasFailed)
        assertEquals("build سبز نشد", timeline.failureReason)
    }

    @Test
    fun `an out-of-scope diff is reported as its own reason`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.AGENT_FAILED)),
            listOf(comment("🚫 TEC changed files this issue does not cover, so nothing was committed.")),
            now = 400
        )

        assertTrue(timeline.hasFailed)
        assertTrue(timeline.failureReason!!.contains("دامنه"))
    }

    @Test
    fun `QC rework keeps the timeline watching instead of ending it`() {
        val reworking = watching().advance(
            issue(listOf(IssueLabels.NEEDS_REWORK, IssueLabels.AGENT)),
            listOf(comment("🛑 QC asks for rework (round 1/2).")),
            now = 400
        )
        assertFalse(reworking.isFinished)

        // Two failed rounds is where it stops, and that reason names the person who has to act.
        val stuck = watching().advance(
            issue(listOf(IssueLabels.NEEDS_REWORK, IssueLabels.NEEDS_HUMAN)),
            emptyList(),
            now = 500
        )
        assertTrue(stuck.isFinished)
        assertTrue(stuck.failureReason!!.contains("QC"))
    }

    @Test
    fun `the agent's token spend is read off its own comment`() {
        val timeline = watching().advance(
            issue(listOf(IssueLabels.DONE)),
            listOf(
                comment(
                    """
                    ### 💸 Token spend for #7

                    | | tokens |
                    | --- | ---: |
                    | Prompt (input) | 12,000 |
                    | Output | 1,500 |
                    | **Total** | **13,500** |

                    Model `x` · 4 model calls · cost **$0.00**

                    [Workflow run](https://github.com/octocat/p/actions/runs/99)
                    """.trimIndent()
                ),
                comment("✅ TEC implemented this and squash-merged it.")
            ),
            now = 400
        )

        val agentStep = timeline.steps.first { it.stage == CommandStage.AGENT_RUNNING }
        assertEquals(13_500, agentStep.tokens)
        assertEquals("https://github.com/octocat/p/actions/runs/99", agentStep.artefactUrl)
    }

    @Test
    fun `repeated polls of an unchanged state do not reset the clock`() {
        val first = watching().advance(issue(listOf(IssueLabels.AGENT)), emptyList(), now = 1_000)
        val second = first.advance(issue(listOf(IssueLabels.AGENT)), emptyList(), now = 9_000)

        val step = second.steps.first { it.stage == CommandStage.QUEUED }
        assertEquals(1_000L, step.startedAt)
        assertEquals(8_000L, step.elapsedMs(now = 9_000))
    }

    @Test
    fun `a local failure stops at the running step`() {
        val timeline = CommandTimeline.starting(now = 0, refining = true)
            .atLocalStage(CommandStage.UNDERSTANDING, now = 50)
            .failedLocally("متوجه دستور نشدم", now = 80)

        assertEquals(StepState.DONE, state(timeline, CommandStage.REFINING))
        assertEquals(StepState.FAILED, state(timeline, CommandStage.UNDERSTANDING))
        assertEquals(StepState.PENDING, state(timeline, CommandStage.EXECUTING))
        assertTrue(timeline.isFinished)
        assertEquals("متوجه دستور نشدم", timeline.summary)
    }

    @Test
    fun `total tokens add up across the app's half and the agent's`() {
        val timeline = watching()
            .withUnderstandingCost(900)
            .advance(
                issue(listOf(IssueLabels.DONE)),
                listOf(comment("| **Total** | **13,500** |"), comment("squash-merged")),
                now = 400
            )

        assertEquals(14_400, timeline.totalTokens)
    }
}
