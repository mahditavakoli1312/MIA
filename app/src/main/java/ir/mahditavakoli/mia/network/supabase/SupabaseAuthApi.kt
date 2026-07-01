package ir.mahditavakoli.mia.network.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class AuthCredentials(val email: String, val password: String)

@Serializable
data class AuthResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val user: AuthUser? = null
)

@Serializable
data class AuthUser(
    val id: String,
    val email: String? = null
)

/**
 * Supabase GoTrue auth client (base URL `<SUPABASE_URL>/auth/v1/`).
 * Only the apikey/anon Authorization headers are needed here — the user isn't
 * authenticated yet, so these calls deliberately use a *separate* Retrofit client
 * from the REST one (see NetworkModule), which sends the per-user token instead.
 */
interface SupabaseAuthApi {

    /** Sign in with email + password; returns a session with an access token. */
    @POST("token")
    suspend fun signInWithPassword(
        @Query("grant_type") grantType: String = "password",
        @Body credentials: AuthCredentials
    ): AuthResponse

    /**
     * Register a new account. Returns a session directly only when "Confirm email"
     * is disabled in Supabase Auth settings; otherwise [AuthResponse.accessToken] is
     * null and the user must sign in afterwards.
     */
    @POST("signup")
    suspend fun signUp(@Body credentials: AuthCredentials): AuthResponse
}
