package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateCommentBody
import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.ContentFile
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubCommit
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.GitHubLabel
import ir.mahditavakoli.mia.network.github.GitHubOwner
import ir.mahditavakoli.mia.network.github.GitHubRepo
import ir.mahditavakoli.mia.network.github.GitHubUser
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.github.PutSecretBody
import ir.mahditavakoli.mia.network.github.RepoPublicKey
import ir.mahditavakoli.mia.network.github.UpdateIssueBody
import ir.mahditavakoli.mia.network.github.WorkflowPermissions
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * In-memory [GitHubApi] for bootstrapper tests: records every call and lets each test
 * dictate the HTTP responses. Beats a mocking framework here since the surface is small
 * and the project ships no mock library.
 */
class FakeGitHubApi : GitHubApi {

    var owner = "octocat"
    var repoName = "test-repo"
    var defaultBranch = "main"
    var publicKey = RepoPublicKey(keyId = "key-123", key = "cHVibGljLWtleQ==")

    // Recorded calls.
    var createRepoBody: CreateRepoBody? = null
    var generateBody: GenerateFromTemplateBody? = null
    var generateTemplate: Pair<String, String>? = null
    val putContents = mutableListOf<Pair<String, PutContentBody>>()
    val createdLabels = mutableListOf<CreateLabelBody>()
    var putSecretName: String? = null
    var putSecretBody: PutSecretBody? = null
    /** Every secret written, in order — the bootstrapper may store more than one. */
    val putSecrets = mutableListOf<Pair<String, PutSecretBody>>()

    /**
     * Files this repo "contains", keyed by repo path. Anything not in here answers 404, which
     * is what an older bootstrap that shipped fewer files looks like.
     */
    val contents = mutableMapOf<String, String>()

    /** Issues this repo "contains", newest first — the order GitHub's list endpoint uses. */
    val issues = mutableListOf<GitHubIssueDetail>()

    /** Comments this repo holds, keyed by issue number and kept oldest-first. */
    val issueComments = mutableMapOf<Int, MutableList<GitHubIssueComment>>()

    /** Commits this repo holds, newest first — where TEC's `Token-Spend:` trailers live. */
    val commits = mutableListOf<GitHubCommit>()

    /**
     * Repo names whose commit / comment reads throw, which is what a repo that was never created
     * — or was renamed out from under a project — looks like to the spend screen.
     */
    var failCommitsFor: String? = null
    var failCommentsFor: String? = null

    // Response controls (default: everything succeeds).
    var putContentResponse: () -> Response<Unit> = { Response.success(Unit) }
    var labelResponse: (CreateLabelBody) -> Response<Unit> = { Response.success(Unit) }
    /** Keyed by secret name, so a test can fail one secret and let the other through. */
    var putSecretResponse: (String) -> Response<Unit> = { Response.success(Unit) }

    private fun repo() = GitHubRepo(
        name = repoName,
        fullName = "$owner/$repoName",
        htmlUrl = "https://github.com/$owner/$repoName",
        owner = GitHubOwner(owner),
        defaultBranch = defaultBranch
    )

    override suspend fun getAuthenticatedUser(): GitHubUser = GitHubUser(owner)

    override suspend fun getAuthenticatedUserResponse(): Response<GitHubUser> =
        Response.success(GitHubUser(owner))

    override suspend fun createRepo(body: CreateRepoBody): GitHubRepo {
        createRepoBody = body
        return repo()
    }

    override suspend fun generateFromTemplate(
        templateOwner: String,
        templateRepo: String,
        body: GenerateFromTemplateBody
    ): GitHubRepo {
        generateTemplate = templateOwner to templateRepo
        generateBody = body
        return repo()
    }

    /** Bodies passed to createIssue, in order. */
    val createdIssues = mutableListOf<CreateIssueBody>()

    /** Labels this repo defines; what listLabels answers. */
    val labels = mutableListOf<GitHubLabel>()

    override suspend fun createIssue(
        owner: String,
        repo: String,
        body: CreateIssueBody
    ): GitHubIssueDetail {
        createdIssues += body
        val created = GitHubIssueDetail(
            number = (issues.maxOfOrNull { it.number } ?: 0) + 1,
            title = body.title,
            body = body.body,
            state = "open",
            user = GitHubUser(this.owner),
            labels = body.labels.orEmpty().map { GitHubLabel(it) },
            createdAt = "2026-01-01T00:00:00Z",
            htmlUrl = "https://github.com/${this.owner}/$repo/issues/1"
        )
        // Newest first, matching the order GitHub's list endpoint returns.
        issues.add(0, created)
        return created
    }

    override suspend fun listLabels(
        owner: String,
        repo: String,
        perPage: Int,
        page: Int
    ): List<GitHubLabel> = labels.drop((page - 1) * perPage).take(perPage)

    override suspend fun listIssues(
        owner: String,
        repo: String,
        state: String,
        perPage: Int,
        page: Int
    ): List<GitHubIssueDetail> {
        val matching = issues.filter { state == "all" || it.state == state }
        return matching.drop((page - 1) * perPage).take(perPage)
    }

    /**
     * `since` is applied only as "was this issue passed to the fake as updated" — the fake keeps
     * no per-issue updated_at, so tests that care about the window assert on the argument
     * recorded here instead.
     */
    val listedSince = mutableListOf<String>()

    override suspend fun listIssuesUpdatedSince(
        owner: String,
        repo: String,
        state: String,
        since: String,
        perPage: Int,
        page: Int
    ): List<GitHubIssueDetail> {
        listedSince += since
        return listIssues(owner, repo, state, perPage, page)
    }

    /** Every state PATCH, in order: issue number to the state it was moved to. */
    val issueStateUpdates = mutableListOf<Pair<Int, String>>()

    var updateIssueResponse: () -> Response<Unit> = { Response.success(Unit) }

    override suspend fun updateIssue(
        owner: String,
        repo: String,
        number: Int,
        body: UpdateIssueBody
    ): Response<Unit> {
        issueStateUpdates += number to body.state
        val response = updateIssueResponse()
        // Only a successful PATCH moves the stored issue, so a test that fails the call still
        // sees the old state on a re-read — as it would against the real API.
        if (response.isSuccessful) {
            val index = issues.indexOfFirst { it.number == number }
            if (index >= 0) issues[index] = issues[index].copy(state = body.state)
        }
        return response
    }

    override suspend fun getIssue(owner: String, repo: String, number: Int): GitHubIssueDetail =
        issues.firstOrNull { it.number == number }
            ?: throw NoSuchElementException("no issue #$number")

    override suspend fun listIssueComments(
        owner: String,
        repo: String,
        number: Int,
        perPage: Int,
        page: Int
    ): List<GitHubIssueComment> =
        issueComments[number].orEmpty().drop((page - 1) * perPage).take(perPage)

    /**
     * The repo-wide comment feed, which the spend screen reads instead of one request per issue.
     * Flattened from [issueComments] so a test that seeds a thread gets it here too, and stamped
     * with the `issue_url` GitHub sends on this endpoint — that field is the only thing tying a
     * comment back to its issue once the issue number is no longer in the request.
     */
    override suspend fun listRepoIssueComments(
        owner: String,
        repo: String,
        perPage: Int,
        page: Int,
        sort: String,
        direction: String
    ): List<GitHubIssueComment> {
        if (repo == failCommentsFor) throw IllegalStateException("no repo $repo")
        return issueComments.entries
            .sortedBy { it.key }
            .flatMap { (number, thread) ->
                thread.map { it.copy(issueUrl = "https://api.github.com/repos/$owner/$repo/issues/$number") }
            }
            .drop((page - 1) * perPage)
            .take(perPage)
    }

    override suspend fun listCommits(
        owner: String,
        repo: String,
        perPage: Int,
        page: Int
    ): List<GitHubCommit> {
        if (repo == failCommitsFor) throw IllegalStateException("no repo $repo")
        return commits.drop((page - 1) * perPage).take(perPage)
    }

    override suspend fun createIssueComment(
        owner: String,
        repo: String,
        number: Int,
        body: CreateCommentBody
    ): GitHubIssueComment {
        val thread = issueComments.getOrPut(number) { mutableListOf() }
        val comment = GitHubIssueComment(
            id = (thread.size + 1).toLong(),
            body = body.body,
            user = GitHubUser(this.owner),
            createdAt = "2026-01-01T00:00:00Z"
        )
        thread += comment
        return comment
    }

    override suspend fun getContent(owner: String, repo: String, path: String): Response<ContentFile> {
        val text = contents[path] ?: return Response.error(404, "".toResponseBody(null))
        return Response.success(
            ContentFile(
                path = path,
                sha = "sha-" + path.hashCode(),
                // GitHub wraps its base64 at 60 columns; reproduce that so the decode path is
                // actually exercised rather than accidentally passing on unwrapped input.
                content = java.util.Base64.getMimeEncoder(60, "\n".toByteArray())
                    .encodeToString(text.toByteArray(Charsets.UTF_8))
            )
        )
    }

    override suspend fun putContent(
        owner: String,
        repo: String,
        path: String,
        body: PutContentBody
    ): Response<Unit> {
        putContents += path to body
        return putContentResponse()
    }

    override suspend fun createLabel(owner: String, repo: String, body: CreateLabelBody): Response<Unit> {
        createdLabels += body
        return labelResponse(body)
    }

    /** Every workflow-permissions block written, in order. */
    val putWorkflowPermissions = mutableListOf<WorkflowPermissions>()

    /** What the repo's Actions settings currently say. Tests override it to start "off". */
    var workflowPermissionsResponse: () -> Response<WorkflowPermissions> = {
        Response.success(
            WorkflowPermissions(
                defaultWorkflowPermissions = "read",
                canApprovePullRequestReviews = false
            )
        )
    }

    var putWorkflowPermissionsResponse: () -> Response<Unit> = { Response.success(Unit) }

    override suspend fun getRepoPublicKey(owner: String, repo: String): RepoPublicKey = publicKey

    override suspend fun putActionsSecret(
        owner: String,
        repo: String,
        name: String,
        body: PutSecretBody
    ): Response<Unit> {
        putSecretName = name
        putSecretBody = body
        putSecrets += name to body
        return putSecretResponse(name)
    }

    override suspend fun getWorkflowPermissions(
        owner: String,
        repo: String
    ): Response<WorkflowPermissions> = workflowPermissionsResponse()

    override suspend fun putWorkflowPermissions(
        owner: String,
        repo: String,
        body: WorkflowPermissions
    ): Response<Unit> {
        putWorkflowPermissions += body
        return putWorkflowPermissionsResponse()
    }

    companion object {
        /** Helper for building an error response with the given status code. */
        fun error(code: Int): Response<Unit> = Response.error(code, "".toResponseBody(null))
    }
}
