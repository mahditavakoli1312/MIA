package ir.mahditavakoli.mia.data.model

/**
 * The label names the whole pipeline agrees on, in one place because three layers read them: the
 * workflows on GitHub, [GitHubRepository][ir.mahditavakoli.mia.data.repository.GitHubRepository]
 * when it opens an issue, and the UI when it decides what a row is.
 */
object IssueLabels {
    /** Queued for the TEC coding agent. */
    const val AGENT = "by-agent"

    /** TEC has claimed this issue and is working on it right now. */
    const val AGENT_RUNNING = "agent-running"

    /** TEC stopped short of a merge. Re-queue it by hand once you know why. */
    const val AGENT_FAILED = "agent-failed"

    /** TEC merged the work for this issue. */
    const val DONE = "done"

    /** A long-form intent waiting for the PO agent to decompose it into TEC-sized issues. */
    const val BRIEF = "brief"

    /** The PO decomposed this brief; its plan is the checklist comment on it. */
    const val BRIEF_PLANNED = "brief-planned"

    /** The PO could not produce a usable plan — the brief waits for a human. */
    const val BRIEF_FAILED = "brief-failed"

    /** A child issue whose prerequisites have not landed yet. */
    const val BLOCKED = "blocked"

    /** QC reviewed TEC's pull request for this issue and asked for changes before merging. */
    const val NEEDS_REWORK = "needs-rework"

    /** Two QC rework rounds were not enough. The agent queue skips this issue from now on. */
    const val NEEDS_HUMAN = "needs-human"
}

/** Where one brief stands, read from the labels the decomposition workflow maintains. */
enum class BriefStatus {
    /** Filed, not yet decomposed (or the workflow has not run yet). */
    PENDING,

    /** Decomposed into child issues. */
    PLANNED,

    /** The PO could not decompose it. */
    FAILED
}

/**
 * One GitHub issue as the app shows it: the fields the UI actually renders, with GitHub's
 * "state" string already collapsed into [isOpen].
 *
 * Deliberately not the wire type — the issues screen shouldn't have to know that a closed
 * issue is `state == "closed"`, or that a row carrying `pull_request` was never an issue.
 */
data class RepoIssue(
    val number: Int,
    val title: String,
    val body: String?,
    val isOpen: Boolean,
    val author: String?,
    /** ISO-8601 UTC as GitHub sends it, e.g. "2026-08-28T09:15:00Z"; null if absent. */
    val createdAt: String?,
    val commentCount: Int,
    val labels: List<String>,
    val htmlUrl: String
) {
    /** True for a long-form intent, which the app counts and lists apart from ordinary issues. */
    val isBrief: Boolean get() = IssueLabels.BRIEF in labels

    /**
     * True when QC's gate has taken this issue out of the agent queue for good: two rework rounds
     * were not enough. Worth showing in a list, because nothing will happen to it until a person
     * acts — unlike `agent-failed`, which the Re-do button alone can clear.
     */
    val needsHuman: Boolean get() = IssueLabels.NEEDS_HUMAN in labels

    /** True while QC has sent the last attempt back and the issue is waiting for another one. */
    val needsRework: Boolean get() = IssueLabels.NEEDS_REWORK in labels && !needsHuman

    /** Meaningful only when [isBrief]; the two outcome labels are set by the PO workflow. */
    val briefStatus: BriefStatus
        get() = when {
            IssueLabels.BRIEF_PLANNED in labels -> BriefStatus.PLANNED
            IssueLabels.BRIEF_FAILED in labels -> BriefStatus.FAILED
            else -> BriefStatus.PENDING
        }
}

/** One comment under an issue. */
data class IssueComment(
    val id: Long,
    val author: String?,
    val body: String,
    val createdAt: String?
)

/**
 * Everything the app knows about one repo's issues after a single read.
 *
 * Both tabs of the issues screen and the count chips on the project card come from this one
 * list, so opening a project costs one paged read rather than one call per state.
 */
data class IssueList(
    val issues: List<RepoIssue>,
    /** True when the repo has more issues than MIA paged through. */
    val isTruncated: Boolean = false
) {
    /** The long-form intents, which are work *requests* rather than work. */
    val briefs: List<RepoIssue> get() = issues.filter { it.isBrief }

    /** Everything else — the issues that represent one piece of implementable work. */
    val ordinary: List<RepoIssue> get() = issues.filterNot { it.isBrief }

    /**
     * Counted apart from each other on purpose: a brief is one intent that will *become* several
     * issues, so adding it to the open count would make a project look like it has more work
     * queued than it does, and hide the one number that matters — how many intents are still
     * waiting to be decomposed.
     */
    val counts: IssueCounts
        get() = IssueCounts(
            open = ordinary.count { it.isOpen },
            closed = ordinary.count { !it.isOpen },
            briefs = BriefCounts(
                total = briefs.count { it.isOpen },
                pending = briefs.count { it.isOpen && it.briefStatus == BriefStatus.PENDING },
                failed = briefs.count { it.isOpen && it.briefStatus == BriefStatus.FAILED }
            ),
            isTruncated = isTruncated
        )

    fun withState(open: Boolean): List<RepoIssue> = issues.filter { it.isOpen == open }

    companion object {
        val EMPTY = IssueList(issues = emptyList())
    }
}

/**
 * How many issues a project's repo has, split the way the card shows them.
 *
 * [isTruncated] is true when the repo has more issues than MIA is willing to page through
 * (see `GitHubRepository.ISSUE_PAGE_LIMIT`) — the counts are then a floor, not a total, and the
 * card says so rather than quietly under-reporting.
 */
data class IssueCounts(
    val open: Int,
    val closed: Int,
    val briefs: BriefCounts = BriefCounts(),
    val isTruncated: Boolean = false
)

/**
 * The open briefs on one project, and how many of them are not yet plans.
 *
 * [pending] is the number the card leads with: a brief nobody has decomposed is the one state
 * where the user is waiting on the system rather than the other way round.
 */
data class BriefCounts(
    val total: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0
) {
    val isEmpty: Boolean get() = total == 0
}
