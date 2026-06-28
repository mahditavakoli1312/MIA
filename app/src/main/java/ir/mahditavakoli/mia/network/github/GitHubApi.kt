package ir.mahditavakoli.mia.network.github

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Thin GitHub REST v3 client. Auth (Authorization / Accept headers) is added
 * globally by an OkHttp interceptor — see NetworkModule.
 */
interface GitHubApi {

    /** The user the token belongs to; used as the owner for repo/issue URLs. */
    @GET("user")
    suspend fun getAuthenticatedUser(): GitHubUser

    /** Creates a repo under the authenticated user's account. */
    @POST("user/repos")
    suspend fun createRepo(@Body body: CreateRepoBody): GitHubRepo

    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: CreateIssueBody
    ): GitHubIssue
}
