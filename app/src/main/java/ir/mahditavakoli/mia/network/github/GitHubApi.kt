package ir.mahditavakoli.mia.network.github

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
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
     * The same list, narrowed to issues touched at or after [since] (ISO-8601 UTC).
     *
     * A separate method rather than a nullable query on [listIssues]: Retrofit reads the
     * annotated declaration, not the synthetic bridge Kotlin generates for a default argument,
     * so an optional parameter here would silently become a required one. The background
     * finished-work watcher is the only caller, and "everything since I last looked" is the only
     * question it asks.
     */
    @GET("repos/{owner}/{repo}/issues")
    suspend fun listIssuesUpdatedSince(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String,
        @Query("since") since: String,
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

    /**
     * Closes or reopens one issue. Returns the raw [Response] because this is only ever called
     * as a best-effort mirror of a Supabase write: the caller wants to know it failed without
     * having an exception thrown through the task update that already succeeded.
     */
    @PATCH("repos/{owner}/{repo}/issues/{number}")
    suspend fun updateIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Body body: UpdateIssueBody
    ): Response<Unit>

    /** Comments on one issue, oldest first — the order GitHub's own issue page shows. */
    @GET("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun listIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): List<GitHubIssueComment>

    /**
     * Every issue comment in the repo, newest first — one read instead of one per issue.
     *
     * This is what makes the spend screen affordable: the PO and QC agents report what they spent
     * in their own comments, and asking for them issue by issue would be one request per issue.
     */
    @GET("repos/{owner}/{repo}/issues/comments")
    suspend fun listRepoIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int,
        @Query("sort") sort: String = "created",
        @Query("direction") direction: String = "desc"
    ): List<GitHubIssueComment>

    /** Commits on the default branch, newest first; carries the `Token-Spend:` trailers. */
    @GET("repos/{owner}/{repo}/commits")
    suspend fun listCommits(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int,
        @Query("page") page: Int
    ): List<GitHubCommit>

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

    @GET("repos/{owner}/{repo}/actions/permissions/workflow")
    suspend fun getWorkflowPermissions(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<WorkflowPermissions>

    /**
     * Both fields are optional to GitHub, but this sends them together on purpose: MIA reads the
     * current block first and writes back the `default_workflow_permissions` it found, so
     * turning the pull-request switch on can never quietly change the other half of a setting
     * page the user may have deliberately tightened.
     */
    @PUT("repos/{owner}/{repo}/actions/permissions/workflow")
    suspend fun putWorkflowPermissions(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: WorkflowPermissions
    ): Response<Unit>
}
