package ir.mahditavakoli.mia.network.github

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Thin GitHub REST v3 client. Auth (Authorization / Accept headers) is added
 * globally by an OkHttp interceptor — see NetworkModule.
 */
interface GitHubApi {

    /** The user the token belongs to; used as the owner for repo/issue URLs. */
    @GET("user")
    suspend fun getAuthenticatedUser(): GitHubUser

    /**
     * Same call, but the full [Response] so callers can read the `X-OAuth-Scopes`
     * header to verify the token carries `repo` + `workflow` (needed to push workflow
     * files). Used only for the login-time scope check.
     */
    @GET("user")
    suspend fun getAuthenticatedUserResponse(): Response<GitHubUser>

    /** Creates a repo under the authenticated user's account. */
    @POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoBody): GitHubRepo

    /** Creates a repo from a template repository (POST .../generate). */
    @POST("repos/{templateOwner}/{templateRepo}/generate")
    suspend fun generateFromTemplate(
        @Path("templateOwner") templateOwner: String,
        @Path("templateRepo") templateRepo: String,
        @Body body: GenerateFromTemplateBody
    ): GitHubRepo

    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateIssueBody
    ): GitHubIssueDetail

    /**
     * Lists issues, newest first. [state] is "open", "closed" or "all".
     *
     * GitHub returns pull requests from this endpoint too, so callers must drop rows whose
     * `pull_request` field is set. Paging is explicit (no default arguments — Retrofit sees the
     * synthetic bridge Kotlin generates for those, not the annotated method).
     */
    @GET("repos/{owner}/{repo}/issues")
    suspend fun listIssues(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): List<GitHubIssueDetail>

    /**
     * The labels this repo defines — what the "new issue" sheet offers. Every MIA-bootstrapped
     * repo has at least `by-agent` and `done` (see RepoBootstrapper), but a repo can carry any
     * labels its owner has added, so the list is read rather than assumed.
     */
    @GET("repos/{owner}/{repo}/labels")
    suspend fun listLabels(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): List<GitHubLabel>

    @GET("repos/{owner}/{repo}/issues/{number}")
    suspend fun getIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int
    ): GitHubIssueDetail

    /** Comments on one issue, oldest first — the order GitHub's own issue page shows. */
    @GET("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun listIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): List<GitHubIssueComment>

    @POST("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun createIssueComment(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Body body: CreateCommentBody
    ): GitHubIssueComment

    /**
     * Reads one file. Returns the raw [Response] because "this repo doesn't have that file"
     * is an ordinary answer here (repos bootstrapped by older MIA versions carry a different
     * set), and a 404 should read as data rather than as a thrown exception.
     */
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String
    ): Response<ContentFile>

    /**
     * Create or update a file. [path] is the repo-relative path (may contain slashes —
     * `encoded = true` keeps them as path separators rather than escaping them).
     */
    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path(value = "path", encoded = true) path: String,
        @Body body: PutContentBody
    ): Response<Unit>

    /** Returns the raw [Response] so a 422 (label already exists) can be tolerated. */
    @POST("repos/{owner}/{repo}/labels")
    suspend fun createLabel(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateLabelBody
    ): Response<Unit>

    @GET("repos/{owner}/{repo}/actions/secrets/public-key")
    suspend fun getRepoPublicKey(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): RepoPublicKey

    @PUT("repos/{owner}/{repo}/actions/secrets/{name}")
    suspend fun putActionsSecret(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("name") name: String,
        @Body body: PutSecretBody
    ): Response<Unit>
}
