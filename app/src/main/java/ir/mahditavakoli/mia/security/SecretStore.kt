package ir.mahditavakoli.mia.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ir.mahditavakoli.mia.BuildConfig

/**
 * Secure, on-device storage for secrets the user enters at runtime:
 *  - the Gemini API key used for on-device voice→intent classification, and
 *  - the OpenRouter API key MIA pushes into each repo as the OPENROUTER_API_KEY Actions
 *    secret so the CI issue agent (OpenCode) can run on a free OpenRouter model.
 *
 * Backed by [EncryptedSharedPreferences] (AES-256-GCM, key held in the Android Keystore),
 * which is the secure counterpart to the plain-text session prefs used elsewhere. Each
 * runtime value wins over its optional [BuildConfig] build-time default, so a developer can
 * bake in a key while still letting users override it from Settings.
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

    /** The OpenRouter API key for the CI agent: runtime override, else build default, else null. */
    val agentApiKey: String?
        get() = prefs.getString(KEY_OPENROUTER, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENROUTER_API_KEY.takeIf { it.isNotBlank() }

    /** What the user last typed in Settings (without the BuildConfig fallback), for the field. */
    val agentApiKeyOverride: String
        get() = prefs.getString(KEY_OPENROUTER, "").orEmpty()

    fun saveAgentApiKey(value: String) {
        prefs.edit().putString(KEY_OPENROUTER, value.trim()).apply()
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
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_AGENT_DEFAULT = "agent_handled_by_default"
    }
}
