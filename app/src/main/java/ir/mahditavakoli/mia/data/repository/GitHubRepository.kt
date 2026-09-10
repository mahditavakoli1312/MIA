package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.model.IssueComment
import ir.mahditavakoli.mia.data.model.IssueLabels
import ir.mahditavakoli.mia.data.model.IssueList
import ir.mahditavakoli.mia.data.model.ProjectType
import ir.mahditavakoli.mia.data.model.RepoIssue
import ir.mahditavakoli.mia.data.model.TokenUsage
import ir.mahditavakoli.mia.network.github.CreateCommentBody
import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.UpdateIssueBody
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.AgentRole
import ir.mahditavakoli.mia.network.openrouter.providerFor
import ir.mahditavakoli.mia.text.PersianText
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
 * file, labels, the `OPENROUTER_API_KEY` / `MINIMAX_API_KEY` secrets), and agent-handled tasks
 * are opened already labeled `by-agent` so the workflow fires immediately.
 */
class GitHubRepository(
    private val api: GitHubApi,
    val isConfigured: Boolean,
    private val bootstrapper: RepoBootstrapper,
    private val agentModelMigrator: AgentModelMigrator,
    /** Refreshes an existing repo's `.github` AI-team files — see [updateTeamFiles]. */
    private val teamFilesUpdater: TeamFilesUpdater,
    /**
     * The OpenRouter keys pushed into each new repo as Actions secrets, read at the moment a
     * repo is created rather than captured up front — a key the user saves in Settings must
     * reach the next repo without the app being restarted. Providers rather than the
     * [ir.mahditavakoli.mia.security.SecretStore] itself so this class stays plain Kotlin,
     * testable off-device; same shape the intent classifiers already use.
     */
    private val agentApiKeyProvider: () -> String?,
    private val agentFallbackApiKeyProvider: () -> String?,
    /** The MiniMax platform key, for repos pointed at a first-party MiniMax model. */
    private val miniMaxApiKeyProvider: () -> String? = { null },
    private val createPrivate: Boolean = true
) {
    // The authenticated user's login, resolved once and reused as the repo/issue owner.
    @Volatile
    private var cachedOwner: String? = null

    /**
     * @param projectType what the repo is going to hold. It picks the conventions file every
     *        agent prompt injects and the design system the team builds UI from, so getting it
     *        wrong is not cosmetic — a web repo bootstrapped as Android tells its own agents to
     *        write Jetpack Compose.
     */
    suspend fun createRepoForProject(
        projectName: String,
        projectType: ProjectType = ProjectType.DEFAULT
    ): Result<RepoBootstrapper.Result> = runCatching {
        bootstrapper.bootstrap(
            owner = owner(),
            name = repoNameFor(projectName),
            description = "Project «$projectName» — managed by MIA",
            private = createPrivate,
            agentApiKey = agentApiKeyProvider(),
            agentFallbackApiKey = agentFallbackApiKeyProvider(),
            miniMaxApiKey = miniMaxApiKeyProvider(),
            projectType = projectType
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
    ): Result<RepoIssue> = runCatching {
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
        ).toRepoIssue()
    }

    /**
     * Opens an issue the user wrote themselves, with whichever labels they picked.
     *
     * The sibling of [createIssueForTask], which exists to mirror a task MIA just stored in
     * Supabase. This one writes only to GitHub: there is no task behind it, and the labels are
     * the user's choice rather than derived from the agent-handled setting. Including
     * [AGENT_LABEL] here has the same effect as anywhere else — the workflow picks the issue up
     * as soon as it is opened.
     */
    suspend fun createIssue(
        projectName: String,
        title: String,
        body: String?,
        labels: List<String>
    ): Result<RepoIssue> = runCatching {
        require(title.isNotBlank()) { "عنوان ایشو خالی است" }
        api.createIssue(
            owner = owner(),
            repo = repoNameFor(projectName),
            body = CreateIssueBody(
                title = title.trim(),
                body = body?.trim()?.takeIf { it.isNotEmpty() },
                labels = labels.takeIf { it.isNotEmpty() }
            )
        ).toRepoIssue()
    }

    /**
     * Files one long-form intent as a single issue labelled [BRIEF_LABEL].
     *
     * Deliberately NOT labelled [AGENT_LABEL]: a brief is a week of work described in a
     * paragraph, and handing it straight to TEC — a small model that reads one issue and edits
     * files — produces either nothing or a mess. The `brief` label triggers the PO decomposition
     * workflow instead, which turns it into several TEC-sized issues and queues the ones that can
     * start now.
     *
     * The body is the user's own words, with the success criterion appended under its own heading
     * when they gave one — the PO reads prose, so nothing is templated on the way out.
     */
    suspend fun createBrief(
        projectName: String,
        title: String,
        description: String,
        successCriteria: String? = null
    ): Result<RepoIssue> = runCatching {
        require(title.isNotBlank()) { "عنوان نیت خالی است" }
        require(description.isNotBlank()) { "شرح نیت خالی است" }
        val body = buildString {
            append(description.trim())
            successCriteria?.trim()?.takeIf { it.isNotEmpty() }?.let { criteria ->
                append("\n\n## معیار موفقیت\n")
                append(criteria)
            }
        }
        api.createIssue(
            owner = owner(),
            repo = repoNameFor(projectName),
            body = CreateIssueBody(
                title = title.trim(),
                body = body,
                labels = listOf(BRIEF_LABEL)
            )
        ).toRepoIssue()
    }

    /**
     * The label names this project's repo defines, for the "new issue" sheet to offer.
     *
     * [AGENT_LABEL] is guaranteed to be in the result even if the repo somehow lacks it: it is
     * the one label that changes what happens to an issue, and GitHub creates a missing label
     * on demand when an issue is opened with it, so offering it is always safe.
     */
    suspend fun labelsFor(projectName: String): Result<List<String>> = runCatching {
        val labels = api.listLabels(
            owner = owner(),
            repo = repoNameFor(projectName),
            perPage = ISSUE_PAGE_SIZE,
            page = 1
        ).map { it.name }
        if (labels.contains(AGENT_LABEL)) labels else listOf(AGENT_LABEL) + labels
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

    /**
     * Issues of this project's repo touched since [since] (ISO-8601 UTC) — the read the
     * background watcher does, and the only one in this class that is not about what is on
     * screen right now.
     *
     * Narrowed by `since` rather than filtered client-side because the watcher runs on a timer
     * against every project the user has: pulling three full pages per repo every fifteen
     * minutes would spend the rate limit that the screens actually need.
     */
    suspend fun issuesUpdatedSince(projectName: String, since: String): Result<List<RepoIssue>> =
        runCatching {
            val owner = owner()
            val repo = repoNameFor(projectName)
            val collected = mutableListOf<RepoIssue>()
            for (page in 1..ISSUE_PAGE_LIMIT) {
                val batch = api.listIssuesUpdatedSince(
                    owner = owner,
                    repo = repo,
                    state = "all",
                    since = since,
                    perPage = ISSUE_PAGE_SIZE,
                    page = page
                )
                collected += batch.filter { it.pullRequest == null }.map { it.toRepoIssue() }
                if (batch.size < ISSUE_PAGE_SIZE) break
            }
            collected
        }

    /**
     * Mirrors a task's done/undone state onto the issue that task opened: [open] = false closes
     * it, true reopens it.
     *
     * The issue is found by title, because that is all a closing command carries — the model is
     * given project and task names, never issue numbers. Titles are compared on
     * [PersianText.fold]ed forms, the same key the project lookup uses, so a spoken title that
     * differs by ی/ک variants or نیم‌فاصله still finds its issue.
     *
     * Returns null when the project's repo has no issue by that title — a task added before the
     * GitHub token was configured has none, and that is not an error. An issue already in the
     * requested state is returned untouched rather than PATCHed for nothing.
     */
    suspend fun setIssueStateForTask(
        projectName: String,
        taskTitle: String,
        open: Boolean
    ): Result<RepoIssue?> = runCatching {
        val target = PersianText.fold(taskTitle)
        val issue = issuesFor(projectName).getOrThrow().issues
            .firstOrNull { PersianText.fold(it.title) == target }
            ?: return@runCatching null
        if (issue.isOpen == open) return@runCatching issue
        val response = api.updateIssue(
            owner = owner(),
            repo = repoNameFor(projectName),
            number = issue.number,
            body = UpdateIssueBody(state = if (open) "open" else "closed")
        )
        check(response.isSuccessful) { "HTTP ${response.code()}" }
        issue.copy(isOpen = open)
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
     * Hands an issue back to the CI agent: posts `@tec do this : …` as a comment, which is the
     * one trigger the agent workflow listens for besides the [AGENT_LABEL] itself.
     *
     * A comment rather than re-adding the label, because a label MIA has already attached cannot
     * be attached twice — an issue the agent gave up on still carries its history, and re-adding
     * `by-agent` would be a no-op that fires nothing. The workflow puts the issue back in the
     * queue when it sees the comment, so a re-done issue takes its place at the back of the line
     * rather than jumping ahead of what is already waiting.
     *
     * Returns the created comment so the screen can show it without re-reading the thread.
     */
    suspend fun redoIssue(projectName: String, issue: RepoIssue): Result<IssueComment> =
        addIssueComment(projectName, issue.number, redoCommentFor(issue))

    /**
     * The model this project's repo runs its AI team on right now, or null when the repo has no
     * workflow naming one. Read straight from the repo rather than remembered locally: the files
     * are the source of truth, and they can be edited on GitHub without MIA ever seeing it.
     */
    suspend fun agentModelFor(projectName: String): Result<String?> = runCatching {
        agentModelMigrator.currentModel(owner(), repoNameFor(projectName))
    }

    /**
     * The model each role of this project's team runs on right now, read straight from the repo
     * for the same reason [agentModelFor] is: the files are the source of truth, and they can be
     * edited on GitHub without MIA ever seeing it.
     *
     * A role absent from the map is one this repo's files say nothing about — an older bootstrap
     * missing that workflow. A repo from before role-scoped models answers the same id for every
     * role, which is exactly what it runs.
     */
    suspend fun agentModelsFor(projectName: String): Result<AgentModelMigrator.TeamFiles> =
        runCatching { agentModelMigrator.report(owner(), repoNameFor(projectName)) }

    /**
     * Repoints this project's repo at [model]. See [AgentModelMigrator] for what that rewrites.
     *
     * The rewrite is preceded by making sure the repo actually holds the API key that model
     * needs: pointing a repo at a MiniMax model without a `MINIMAX_API_KEY` secret would produce
     * a clean-looking commit and then fail on the next `@tec`, which is the worst of both. The
     * key push is best-effort and its failure is folded into the outcome as a warning rather
     * than aborting the change — a repo whose secrets MIA cannot write can still have the secret
     * added by hand on GitHub.
     */
    suspend fun setAgentModel(
        projectName: String,
        model: String
    ): Result<AgentModelMigrator.Outcome> =
        setAgentModels(projectName, AgentRole.REPO_ROLES.associateWith { model })

    /**
     * Points each role of this project's team at its own model — the multi-role counterpart to
     * [setAgentModel], and what the project's model management screen calls.
     *
     * Every provider named by any of the models has its key pushed first, for the reason
     * [ensureProviderSecret] gives: a repo pointed at MiniMax without a `MINIMAX_API_KEY` secret
     * produces a clean-looking commit and then fails on the next run. With a model per role that
     * matters more, not less — one role on MiniMax is enough to need the key, and the roles that
     * stayed on OpenRouter would keep working and hide the breakage.
     */
    suspend fun setAgentModels(
        projectName: String,
        models: Map<AgentRole, String>
    ): Result<AgentModelMigrator.Outcome> = runCatching {
        val owner = owner()
        val repo = repoNameFor(projectName)
        val secretWarnings = models.values
            .map(::providerFor)
            .toSet()
            .mapNotNull { provider -> ensureProviderSecret(owner, repo, provider) }
        val outcome = agentModelMigrator.setModels(owner, repo, models)
        outcome.copy(failed = outcome.failed + secretWarnings)
    }

    /**
     * Rewrites this project's repo `.github` AI-team files to the versions this build ships,
     * keeping whatever models the repo already runs each role on.
     *
     * It is the answer to every "these files are too old" message the model screen can produce:
     * a repo with one shared `AGENT_MODEL` cannot give QC its own model, and one from before the
     * QC → PO → TEC loop stops at `needs-human` instead of re-scoping. Neither is fixable from
     * the app without replacing the files themselves.
     */
    suspend fun updateTeamFiles(
        projectName: String,
        fallbackModels: Map<AgentRole, String>
    ): Result<TeamFilesUpdater.Outcome> = runCatching {
        teamFilesUpdater.update(owner(), repoNameFor(projectName), fallbackModels)
    }

    /**
     * Pushes [provider]'s API key to the repo, returning null on success (or when there is
     * nothing to do) and a `path to reason` pair the outcome can report otherwise.
     *
     * Only the non-default providers are pushed here. OpenRouter's key is written at bootstrap
     * and re-pushing it on every model change would spend a GitHub write for nothing.
     */
    private suspend fun ensureProviderSecret(
        owner: String,
        repo: String,
        provider: AgentProvider
    ): Pair<String, String>? {
        if (provider == AgentProvider.DEFAULT) return null
        val key = when (provider) {
            AgentProvider.MINIMAX -> miniMaxApiKeyProvider()
            AgentProvider.OPENROUTER -> agentApiKeyProvider()
        }
        if (key.isNullOrBlank()) {
            return provider.secretName to "کلید ${provider.label} در تنظیمات وارد نشده است"
        }
        return bootstrapper.putProviderSecret(owner, repo, provider, key)
            .fold(onSuccess = { null }, onFailure = { provider.secretName to (it.message ?: "خطای نامشخص") })
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

    /**
     * The authenticated user's login, for callers that build their own requests — the spend screen
     * reads commits and repo-wide comments, which are not issue operations and don't belong here.
     * Shared so those reads use the same cached lookup rather than spending a `GET /user` of their
     * own on the rate limit this class is careful about.
     */
    suspend fun ownerLogin(): String = owner()

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
        // Aliases of the one definition in IssueLabels, kept because these names are read all
        // over the app and the model layer is where the pipeline's vocabulary belongs.
        const val AGENT_LABEL = IssueLabels.AGENT

        /** The label the agent workflow attaches once an issue's work is merged. */
        const val DONE_LABEL = IssueLabels.DONE

        /** A long-form intent for the PO agent to decompose — see [createBrief]. */
        const val BRIEF_LABEL = IssueLabels.BRIEF

        /** GitHub's maximum page size for list endpoints. */
        private const val ISSUE_PAGE_SIZE = 100

        /**
         * How many pages of issues (or comments) MIA will pull. Three pages is far more than a
         * MIA-managed project realistically has, and bounds the worst case for a repo that was
         * pointed at something much bigger.
         */
        private const val ISSUE_PAGE_LIMIT = 3

        /**
         * The comment the Re-do button posts: the agent's own trigger word followed by the
         * brief it should work from.
         *
         * The brief is the issue body, falling back to the title for an issue that has none —
         * "@tec do this :" with nothing after it would send the agent off with no instructions
         * at all. The spend footer MIA appends when it opens an issue (see
         * [ir.mahditavakoli.mia.data.model.TokenUsage.asIssueFooter], written below a `---`
         * rule) is dropped: it is bookkeeping about the issue, not part of what to build.
         */
        fun redoCommentFor(issue: RepoIssue): String {
            val brief = issue.body
                ?.substringBefore("\n---\n")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: issue.title.trim()
            return "@tec do this : $brief"
        }

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
