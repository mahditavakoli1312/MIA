package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssue
import ir.mahditavakoli.mia.security.SecretStore
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
 * Creating a repo also wires it up to the Gemini CI agent via [RepoBootstrapper] (workflow
 * file, labels, `GEMINI_API_KEY` secret), and agent-handled tasks are opened already
 * labeled `by-agent` so the workflow fires immediately.
 */
class GitHubRepository(
    private val api: GitHubApi,
    val isConfigured: Boolean,
    private val bootstrapper: RepoBootstrapper,
    private val secretStore: SecretStore,
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
            geminiApiKey = secretStore.geminiApiKey
        )
    }

    suspend fun createIssueForTask(
        projectName: String,
        taskTitle: String,
        description: String?,
        dueDate: String?,
        agentHandled: Boolean
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
