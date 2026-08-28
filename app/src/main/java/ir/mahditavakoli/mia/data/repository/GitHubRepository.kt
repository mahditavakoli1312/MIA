package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.IssueList
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.network.github.CreateCommentBody
import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssue
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import kotlin.math.absoluteValue

/**
 * Mirrors project/task changes onto GitHub: a project becomes a repository, a task
 * becomes an issue in that project's repository.
 *
 * The repo name is derived deterministically from the project name (see [repoNameFor])
 * so that when a task is added later we can re-derive the same repo without storing the
 * mapping anywhere. [isConfigured] is false when no token is set, in which case callers
 * skip GitHub entirely.
 *
 * Creating a repo also wires it up to the OpenCode CI agent via [RepoBootstrapper] (workflow
 * file, labels, `OPENROUTER_API_KEY` secret), and agent-handled tasks are opened already
 * labeled `by-agent` so the workflow fires immediately.
 */
class GitHubRepository(
    private val api: GitHubApi,
    val isConfigured: Boolean,
    private val bootstrapper: RepoBootstrapper,
    private val agentModelMigrator: AgentModelMigrator,
    /**
     * The OpenRouter keys pushed into each new repo as Actions secrets, read at the moment a
     * repo is created rather than captured up front — a key the user saves in Settings must
     * reach the next repo without the app being restarted. Providers rather than the
     * [ir.mahditavakoli.mia.security.SecretStore] itself so this class stays plain Kotlin,
     * testable off-device; same shape the intent classifiers already use.
     */
    private val agentApiKeyProvider: () -> String?,
    private val agentFallbackApiKeyProvider: () -> String?,
    private val createPrivate: Boolean = true
) {
    // The authenticated user's login, resolved once and reused as the repo/issue owner.
    @Volatile
    private var cachedOwner: String? = null

    suspend fun createRepoForProject(projectName: String): Result<RepoBootstrapper.Result> = runCatching {
        bootstrapper.bootstrap(
            owner = owner(),
            name = repoNameFor(projectName),
            description = "Project «$projectName» — managed by MIA",
            private = createPrivate,
            agentApiKey = agentApiKeyProvider(),
            agentFallbackApiKey = agentFallbackApiKeyProvider()
        )
    }

    /**
     * @param usage what MIA's own voice→intent call cost, recorded as a footer on the issue so
     *        the task opens with its spend ledger already started. Null skips the footer.
     * @param usageSharedBy how many issues that single model call produced (see
     *        [TokenUsage.asIssueFooter]).
     */
    suspend fun createIssueForTask(
        projectName: String,
        taskTitle: String,
        description: String?,
        dueDate: String?,
        agentHandled: Boolean,
        usage: TokenUsage? = null,
        usageSharedBy: Int = 1
    ): Result<GitHubIssue> = runCatching {
        val owner = owner()
        val body = buildString {
            // Prefer the Gemini-generated description as the issue body (it's the agent's brief);
            // fall back to a generic line when the intent carried none (e.g. non-voice callers).
            append(
                description?.takeIf { it.isNotBlank() }
                    ?: "Task added via MIA for project «$projectName»."
            )
            if (!dueDate.isNullOrBlank()) append("\n\nDue date: ").append(dueDate)
            // Kept behind a rule so it reads as metadata, not as part of the agent's brief.
            if (usage != null) append("\n\n---\n").append(usage.asIssueFooter(usageSharedBy))
        }
        api.createIssue(
            owner = owner,
            repo = repoNameFor(projectName),
            body = CreateIssueBody(
                title = taskTitle,
                body = body,
                // Attaching "by-agent" at open time is what triggers the agent workflow.
                labels = if (agentHandled) listOf(AGENT_LABEL) else null
            )
        )
    }

    // Reading issues back -----------------------------------------------------

    /**
     * Every issue in this project's repo — open and closed together, newest first.
     *
     * One paged read serves both tabs of the issues screen and the counts on the project card;
     * splitting it per state would double the requests against a rate limit shared with repo
     * bootstrapping. Pull requests are dropped: GitHub returns them from the issues endpoint,
     * but they are not tasks and would inflate every count on the card.
     *
     * Paging stops at [ISSUE_PAGE_LIMIT] pages; the result then reports itself truncated rather
     * than pretending the numbers are totals.
     */
    suspend fun issuesFor(projectName: String): Result<IssueList> = runCatching {
        val owner = owner()
        val repo = repoNameFor(projectName)
        val collected = mutableListOf<RepoIssue>()
        var truncated = false
        for (page in 1..ISSUE_PAGE_LIMIT) {
            val batch = api.listIssues(
                owner = owner,
                repo = repo,
                state = "all",
                perPage = ISSUE_PAGE_SIZE,
                page = page
            )
            collected += batch.filter { it.pullRequest == null }.map { it.toRepoIssue() }
            if (batch.size < ISSUE_PAGE_SIZE) break
            // A full last page means GitHub probably has more than we are willing to fetch.
            if (page == ISSUE_PAGE_LIMIT) truncated = true
        }
        IssueList(issues = collected, isTruncated = truncated)
    }

    /** One issue, re-read from GitHub so the detail screen shows its current state and body. */
    suspend fun issueFor(projectName: String, number: Int): Result<RepoIssue> = runCatching {
        api.getIssue(owner(), repoNameFor(projectName), number).toRepoIssue()
    }

    /**
     * Comments on one issue, oldest first. Paged the same way as [issuesFor] — an issue the CI
     * agent has been working on can easily carry dozens of them.
     */
    suspend fun issueCommentsFor(projectName: String, number: Int): Result<List<IssueComment>> =
        runCatching {
            val owner = owner()
            val repo = repoNameFor(projectName)
            val collected = mutableListOf<IssueComment>()
            for (page in 1..ISSUE_PAGE_LIMIT) {
                val batch = api.listIssueComments(
                    owner = owner,
                    repo = repo,
                    number = number,
                    perPage = ISSUE_PAGE_SIZE,
                    page = page
                )
                collected += batch.map { it.toIssueComment() }
                if (batch.size < ISSUE_PAGE_SIZE) break
            }
            collected
        }

    /**
     * Posts a comment as the token's own user. Returns the created comment so the screen can
     * append it without re-reading the whole thread.
     */
    suspend fun addIssueComment(
        projectName: String,
        number: Int,
        body: String
    ): Result<IssueComment> = runCatching {
        require(body.isNotBlank()) { "متن کامنت خالی است" }
        api.createIssueComment(
            owner = owner(),
            repo = repoNameFor(projectName),
            number = number,
            body = CreateCommentBody(body)
        ).toIssueComment()
    }

    /**
     * The model this project's repo runs its AI team on right now, or null when the repo has no
     * workflow naming one. Read straight from the repo rather than remembered locally: the files
     * are the source of truth, and they can be edited on GitHub without MIA ever seeing it.
     */
    suspend fun agentModelFor(projectName: String): Result<String?> = runCatching {
        agentModelMigrator.currentModel(owner(), repoNameFor(projectName))
    }

    /** Repoints this project's repo at [model]. See [AgentModelMigrator] for what that rewrites. */
    suspend fun setAgentModel(
        projectName: String,
        model: String
    ): Result<AgentModelMigrator.Outcome> = runCatching {
        agentModelMigrator.setModel(owner(), repoNameFor(projectName), model)
    }

    /**
     * Reads the token's granted scopes from the `X-OAuth-Scopes` response header so the UI
     * can warn when `workflow` is missing (uploading workflow files fails without it).
     * Fine-grained tokens omit that header entirely — reported as [TokenScopeCheck.determinable] = false.
     */
    suspend fun verifyTokenScopes(): Result<TokenScopeCheck> = runCatching {
        val response = api.getAuthenticatedUserResponse()
        check(response.isSuccessful) { "HTTP ${response.code()}" }
        val header = response.headers()["X-OAuth-Scopes"]
        val scopes = header.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        TokenScopeCheck(
            determinable = header != null,
            hasRepo = scopes.any { it == "repo" || it.startsWith("repo:") },
            hasWorkflow = scopes.contains("workflow")
        )
    }

    private suspend fun owner(): String =
        cachedOwner ?: api.getAuthenticatedUser().login.also { cachedOwner = it }

    /** Result of inspecting the GitHub token's scopes. */
    data class TokenScopeCheck(
        /** False for fine-grained tokens, which don't expose classic OAuth scopes. */
        val determinable: Boolean,
        val hasRepo: Boolean,
        val hasWorkflow: Boolean
    )

    companion object {
        const val AGENT_LABEL = "by-agent"

        /** GitHub's maximum page size for list endpoints. */
        private const val ISSUE_PAGE_SIZE = 100

        /**
         * How many pages of issues (or comments) MIA will pull. Three pages is far more than a
         * MIA-managed project realistically has, and bounds the worst case for a repo that was
         * pointed at something much bigger.
         */
        private const val ISSUE_PAGE_LIMIT = 3

        /**
         * GitHub repo names may only contain ASCII letters, digits, '.', '-', '_'.
         * Latin project names become a readable slug; names with no usable ASCII
         * characters (e.g. fully-Persian names) fall back to a stable hash so the
         * result is still deterministic for later issue creation.
         */
        fun repoNameFor(projectName: String): String {
            val slug = projectName.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            return slug.ifEmpty { "mia-project-${projectName.hashCode().absoluteValue}" }
        }
    }
}

private fun GitHubIssueDetail.toRepoIssue(): RepoIssue = RepoIssue(
    number = number,
    title = title,
    body = body,
    // GitHub only ever sends "open" or "closed"; treat anything unexpected as closed rather
    // than showing a stale issue as actionable.
    isOpen = state.equals("open", ignoreCase = true),
    author = user?.login,
    createdAt = createdAt,
    commentCount = comments,
    labels = labels.map { it.name },
    htmlUrl = htmlUrl
)

private fun GitHubIssueComment.toIssueComment(): IssueComment = IssueComment(
    id = id,
    author = user?.login,
    body = body,
    createdAt = createdAt
)
