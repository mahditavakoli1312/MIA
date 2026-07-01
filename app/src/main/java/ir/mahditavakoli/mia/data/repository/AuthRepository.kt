package ir.mahditavakoli.mia.data.repository

import ir.mahditavakoli.mia.data.session.SessionManager
import ir.mahditavakoli.mia.network.supabase.AuthCredentials
import ir.mahditavakoli.mia.network.supabase.SupabaseAuthApi

/**
 * Username/password authentication on top of Supabase Auth.
 *
 * Supabase Auth identifies accounts by email, so a username is mapped to a synthetic
 * email (`<username>@mia.app`). The username never leaves the app as a real address,
 * it's purely the local identity the user types. (`.local` is rejected by Supabase's
 * email validator, so a real-looking TLD is required.)
 */
class AuthRepository(
    private val api: SupabaseAuthApi,
    private val sessionManager: SessionManager
) {

    suspend fun login(username: String, password: String): Result<Unit> = runCatching {
        val response = api.signInWithPassword(credentials = credentials(username, password))
        val token = response.accessToken
            ?: error("نام کاربری یا رمز عبور اشتباه است")
        sessionManager.save(token, response.refreshToken, username.trim())
    }

    suspend fun signUp(username: String, password: String): Result<Boolean> = runCatching {
        val response = api.signUp(credentials(username, password))
        val token = response.accessToken
        if (token != null) {
            // "Confirm email" is disabled — we got a session straight away, log them in.
            sessionManager.save(token, response.refreshToken, username.trim())
            true
        } else {
            // Account created but no session yet; caller should prompt to sign in.
            false
        }
    }

    fun logout() = sessionManager.clear()

    private fun credentials(username: String, password: String) =
        AuthCredentials(email = "${username.trim().lowercase()}@mia.app", password = password)
}
