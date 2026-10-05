package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.Project
import ir.mahditavakoli.mia.data.model.SpendEntry
import ir.mahditavakoli.mia.data.model.SpendReport
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.ui.issues.epochMillisFromIso

/**
 * Collects what the whole AI team has spent, from the records it left on GitHub.
 *
 * Two reads per repo, both paged and both bounded:
 *   1. the commits, for TEC's `Token-Spend:` trailers — the one record of an agent run that
 *      outlives the branch and the workflow logs;
 *   2. every issue comment in the repo at once, for the PO and QC footers. One request per issue
 *      would make this screen cost fifty requests on a real project.
 *
 * Plus the app's own calls, which have no GitHub record at all.
 *
 * Bounded on purpose. [PAGE_LIMIT] pages of each, per repo, and the report says so
 * ([SpendReport.isTruncated]) rather than presenting a floor as a total — the issues list already
 * sets that precedent and this screen matches it.
 */
class SpendRepository(
    private val api: GitHubApi,
    private val gitHub: GitHubRepository,
    /**
     * MIA's own spend, normally [LocalSpendStore.entries]. A provider rather than the store
     * itself so this class stays plain Kotlin and testable off-device, the same shape
     * [GitHubRepository]'s key providers use — the store needs a `Context`, and none of the
     * arithmetic here does.
     */
    private val localEntries: () -> List<SpendEntry>
) {

    /**
     * Reads every project's spend, one repo at a time.
     *
     * Sequential rather than parallel for the same reason the issue counts are: this is a screen the
     * user opened deliberately, and firing eight repos' worth of requests at once would spend the
     * shared GitHub rate limit that repo creation and the agent-model picker also draw on.
     *
     * A repo that cannot be read (never created, renamed, deleted) is named in
     * [SpendReport.unreadableProjects] rather than failing the whole screen: one broken project must
     * not hide the numbers for the other seven.
     */
    suspend fun report(projects: List<Project>): Result<SpendReport> = runCatching {
        val entries = mutableListOf<SpendEntry>()
        entries += localEntries()

        if (!gitHub.isConfigured) {
            return@runCatching SpendReport(entries = entries)
        }

        val owner = gitHub.ownerLogin()
        var truncated = false
        val unreadable = mutableListOf<String>()

        for (project in projects) {
            val repo = GitHubRepository.repoNameFor(project.name)
            var readAnything = false

            val commits = runCatching { readCommits(owner, repo) }.getOrNull()
            if (commits != null) {
                readAnything = true
                truncated = truncated || commits.truncated
                entries += commits.entries.map { it.copy(projectName = project.name) }
            }

            val comments = runCatching { readComments(owner, repo) }.getOrNull()
            if (comments != null) {
                readAnything = true
                truncated = truncated || comments.truncated
                entries += comments.entries.map { it.copy(projectName = project.name) }
            }

            if (!readAnything) unreadable += project.name
        }

        SpendReport(
            entries = entries.sortedBy { it.atMillis },
            isTruncated = truncated,
            unreadableProjects = unreadable
        )
    }

    private data class Page(val entries: List<SpendEntry>, val truncated: Boolean)

    private suspend fun readCommits(owner: String, repo: String): Page {
        val entries = mutableListOf<SpendEntry>()
        var truncated = false
        for (page in 1..PAGE_LIMIT) {
            val batch = api.listCommits(owner, repo, perPage = PAGE_SIZE, page = page)
            for (commit in batch) {
                val message = commit.commit.message
                val parsed = SpendParsing.parseCommitTrailer(message) ?: continue
                entries += SpendEntry(
                    role = parsed.role,
                    model = parsed.model,
                    tokens = parsed.tokens,
                    costUsd = parsed.costUsd,
                    issueNumber = SpendParsing.issueNumberFrom(message),
                    atMillis = epochMillisFromIso(commit.commit.author?.date),
                    url = commit.htmlUrl.takeIf { it.isNotBlank() }
                )
            }
            if (batch.size < PAGE_SIZE) return Page(entries, truncated = false)
            truncated = page == PAGE_LIMIT
        }
        return Page(entries, truncated)
    }

    private suspend fun readComments(owner: String, repo: String): Page {
        val entries = mutableListOf<SpendEntry>()
        var truncated = false
        for (page in 1..PAGE_LIMIT) {
            val batch = api.listRepoIssueComments(owner, repo, perPage = PAGE_SIZE, page = page)
            for (comment in batch) {
                for (parsed in SpendParsing.parseRoleComment(comment.body)) {
                    entries += SpendEntry(
                        role = parsed.role,
                        model = parsed.model,
                        tokens = parsed.tokens,
                        costUsd = parsed.costUsd,
                        issueNumber = SpendParsing.issueNumberFromUrl(comment.issueUrl),
                        atMillis = epochMillisFromIso(comment.createdAt),
                        url = comment.htmlUrl.takeIf { it.isNotBlank() }
                    )
                }
            }
            if (batch.size < PAGE_SIZE) return Page(entries, truncated = false)
            truncated = page == PAGE_LIMIT
        }
        return Page(entries, truncated)
    }

    private companion object {
        const val PAGE_SIZE = 100

        /**
         * Three pages of each per repo — 300 commits and 300 comments. Far more than a
         * MIA-managed project has, and a hard ceiling for a repo pointed at something much bigger.
         */
        const val PAGE_LIMIT = 3
    }
}
