package ir.mahditavakoli.mia.data.model

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
)

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
    val counts: IssueCounts
        get() = IssueCounts(
            open = issues.count { it.isOpen },
            closed = issues.count { !it.isOpen },
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
    val isTruncated: Boolean = false
)
