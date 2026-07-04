package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.network.github.CreateIssueBody
import ir.mahditavakoli.mia.network.github.CreateLabelBody
import ir.mahditavakoli.mia.network.github.CreateRepoBody
import ir.mahditavakoli.mia.network.github.GenerateFromTemplateBody
import ir.mahditavakoli.mia.network.github.GitHubApi
import ir.mahditavakoli.mia.network.github.GitHubIssue
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

    // Response controls (default: everything succeeds).
    var putContentResponse: () -> Response<Unit> = { Response.success(Unit) }
    var labelResponse: (CreateLabelBody) -> Response<Unit> = { Response.success(Unit) }
    var putSecretResponse: () -> Response<Unit> = { Response.success(Unit) }

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

    override suspend fun createIssue(owner: String, repo: String, body: CreateIssueBody): GitHubIssue =
        throw UnsupportedOperationException("not used by the bootstrapper")

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
        return putSecretResponse()
    }

    companion object {
        /** Helper for building an error response with the given status code. */
        fun error(code: Int): Response<Unit> = Response.error(code, "".toResponseBody(null))
    }
}
