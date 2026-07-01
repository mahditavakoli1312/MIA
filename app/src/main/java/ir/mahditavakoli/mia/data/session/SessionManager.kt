package ir.mahditavakoli.mia.data.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the signed-in user's Supabase session (access token) across launches and
 * exposes the login state as a [StateFlow] so the UI can switch between the login
 * screen and the main screen reactively.
 *
 * The access token read by [accessToken] is what the Supabase REST interceptor sends
 * as the Bearer token — that's what scopes every request to the current user (once
 * Row Level Security is enabled on the Supabase side).
 */
class SessionManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mia_session", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(prefs.getString(KEY_ACCESS_TOKEN, null) != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /** Current access token, or null when signed out. Read live by the network interceptor. */
    val accessToken: String? get() = prefs.getString(KEY_ACCESS_TOKEN, null)

    /** The username the current session belongs to, for display in the UI. */
    val username: String? get() = prefs.getString(KEY_USERNAME, null)

    fun save(accessToken: String, refreshToken: String?, username: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USERNAME, username)
            .apply()
        _isLoggedIn.value = true
    }

    fun clear() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USERNAME = "username"
    }
}
