package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateCommentBody
import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.ContentFile
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssueComment
import ir.mahditavakoli.mia.network.github.GitHubIssueDetail
import ir.mahditavakoli.mia.network.github.GitHubLabel
import ir.mahditavakoli.mia.network.github.GitHubOwner
import ir.mahditavakoli.mia.network.github.GitHubRepo
import ir.mahditavakoli.mia.network.github.GitHubUser
import ir.mahditavakoli.mia.network.github.PutContentBody
import ir.mahditavakoli.mia.network.github.PutSecretBody
import ir.mahditavakoli.mia.network.github.RepoPublicKey
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

    companion object {
        /** Helper for building an error response with the given status code. */
        fun error(code: Int): Response<Unit> = Response.error(code, "".toResponseBody(null))
    }
}
