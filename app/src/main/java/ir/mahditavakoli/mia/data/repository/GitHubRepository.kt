package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssue
import ir.mahditavakoli.mia.network.github.GitHubRepo
import kotlin.math.absoluteValue

/**
 * Mirrors project/task changes onto GitHub: a project becomes a repository, a task
 * becomes an issue in that project's repository.
 *
 * The repo name is derived deterministically from the project name (see [repoNameFor])
 * so that when a task is added later we can re-derive the same repo without storing the
 * mapping anywhere. [isConfigured] is false when no token is set, in which case callers
 * skip GitHub entirely.
 */
class GitHubRepository(
    private val api: GitHubApi,
    val isConfigured: Boolean,
    private val createPrivate: Boolean = true
) {
    // The authenticated user's login, resolved once and reused as the repo/issue owner.
    @Volatile
    private var cachedOwner: String? = null

    suspend fun createRepoForProject(projectName: String): Result<GitHubRepo> = runCatching {
        api.createRepo(
            CreateRepoBody(
                name = repoNameFor(projectName),
                description = "Project «$projectName» — managed by MIA",
                private = createPrivate
            )
        )
    }

    suspend fun createIssueForTask(
        projectName: String,
        taskTitle: String,
        dueDate: String?
    ): Result<GitHubIssue> = runCatching {
        val owner = owner()
        val body = buildString {
            append("Task added via MIA for project «").append(projectName).append("».")
            if (!dueDate.isNullOrBlank()) append("\n\nDue date: ").append(dueDate)
        }
        api.createIssue(
            owner = owner,
            repo = repoNameFor(projectName),
            body = CreateIssueBody(title = taskTitle, body = body)
        )
    }

    private suspend fun owner(): String =
        cachedOwner ?: api.getAuthenticatedUser().login.also { cachedOwner = it }

    companion object {
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
