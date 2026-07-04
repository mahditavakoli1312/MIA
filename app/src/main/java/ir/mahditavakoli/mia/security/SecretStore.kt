package ir.mahditavakoli.mia.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ir.mahditavakoli.mia.BuildConfig

/**
 * Secure, on-device storage for secrets the user enters at runtime — currently the Gemini
 * API key that MIA pushes into each repo's Actions secrets.
 *
 * Backed by [EncryptedSharedPreferences] (AES-256-GCM, key held in the Android Keystore),
 * which is the secure counterpart to the plain-text session prefs used elsewhere. The
 * runtime value wins over the optional [BuildConfig.GEMINI_API_KEY] build-time default, so
 * a developer can bake in a key while still letting users override it from Settings.
 */
class SecretStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** The Gemini API key: runtime override if present, else the build-time default, else null. */
    val geminiApiKey: String?
        get() = prefs.getString(KEY_GEMINI, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }

    /** What the user last typed in Settings (without the BuildConfig fallback), for the field. */
    val geminiApiKeyOverride: String
        get() = prefs.getString(KEY_GEMINI, "").orEmpty()

    fun saveGeminiApiKey(value: String) {
        prefs.edit().putString(KEY_GEMINI, value.trim()).apply()
    }

    /** Whether newly created tasks are handed to the agent (labeled "by-agent") by default. */
    var agentHandledByDefault: Boolean
        get() = prefs.getBoolean(KEY_AGENT_DEFAULT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_DEFAULT, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "mia_secrets"
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_AGENT_DEFAULT = "agent_handled_by_default"
    }
}
