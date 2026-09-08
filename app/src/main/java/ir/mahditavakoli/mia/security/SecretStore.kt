package ir.mahditavakoli.mia.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ir.mahditavakoli.mia.BuildConfig
import ir.mahditavakoli.mia.network.openrouter.AgentProvider
import ir.mahditavakoli.mia.network.openrouter.DEFAULT_TEXT_MODEL
import ir.mahditavakoli.mia.network.openrouter.agentModelOrNull
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Secure, on-device storage for secrets the user enters at runtime:
 *  - the Gemini API key used for on-device voice→intent classification, and
 *  - the OpenRouter API key MIA pushes into each repo as the OPENROUTER_API_KEY Actions
 *    secret so the CI issue agent (OpenCode) can run on a free OpenRouter model, and
 *  - the MiniMax API key, its paid counterpart: the same key powers the `MiniMax-M3` picker
 *    entry in the app and lands in each repo as the MINIMAX_API_KEY Actions secret.
 *
 * Backed by [EncryptedSharedPreferences] (AES-256-GCM, key held in the Android Keystore),
 * which is the secure counterpart to the plain-text session prefs used elsewhere. Each
 * runtime value wins over its optional [BuildConfig] build-time default, so a developer can
 * bake in a key while still letting users override it from Settings.
 */
class SecretStore(context: Context) {

    private val prefs = openPrefs(context.applicationContext)

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

    /**
     * A second OpenRouter key, only reached once the primary one answers with a limit (HTTP 429
     * rate limit, or 402 out of credit). Free-tier keys are capped per day, so one spent key
     * would otherwise stop typed commands and the CI agent outright until the quota resets.
     * Runtime override first, then the build default, else null (no fallback configured).
     */
    val agentFallbackApiKey: String?
        get() = prefs.getString(KEY_OPENROUTER_FALLBACK, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENROUTER_FALLBACK_API_KEY.takeIf { it.isNotBlank() }

    /** What the user last typed in Settings (without the BuildConfig fallback), for the field. */
    val agentFallbackApiKeyOverride: String
        get() = prefs.getString(KEY_OPENROUTER_FALLBACK, "").orEmpty()

    fun saveAgentFallbackApiKey(value: String) {
        prefs.edit().putString(KEY_OPENROUTER_FALLBACK, value.trim()).apply()
    }

    /**
     * The MiniMax platform key, for the picker entries served by
     * [ir.mahditavakoli.mia.network.openrouter.AgentProvider.MINIMAX]: runtime override,
     * else build default, else null (MiniMax models simply can't run).
     */
    val miniMaxApiKey: String?
        get() = prefs.getString(KEY_MINIMAX, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.MINIMAX_API_KEY.takeIf { it.isNotBlank() }

    /** What the user last typed in Settings (without the BuildConfig fallback), for the field. */
    val miniMaxApiKeyOverride: String
        get() = prefs.getString(KEY_MINIMAX, "").orEmpty()

    fun saveMiniMaxApiKey(value: String) {
        prefs.edit().putString(KEY_MINIMAX, value.trim()).apply()
    }

    /** The key for [provider], whichever store it lives in. Null when none is configured. */
    fun apiKeyFor(provider: AgentProvider): String? = when (provider) {
        AgentProvider.OPENROUTER -> agentApiKey
        AgentProvider.MINIMAX -> miniMaxApiKey
    }

    /**
     * The spare key for [provider], or null when it has no notion of one. Only OpenRouter does:
     * the fallback exists because *free* keys run dry, and MiniMax is a paid account.
     */
    fun fallbackApiKeyFor(provider: AgentProvider): String? = when (provider) {
        AgentProvider.OPENROUTER -> agentFallbackApiKey
        AgentProvider.MINIMAX -> null
    }

    /**
     * Which model the app's own typed-command pipeline runs on, chosen in Settings.
     *
     * Separate from the per-repo agent model on purpose: that one lives in the repo's workflow
     * files on GitHub and is picked per project, while this is a single device-local preference
     * for the two calls MIA makes on the phone. An id that is no longer offered (a stale
     * preference from an older build) falls back to [DEFAULT_TEXT_MODEL] rather than failing
     * every command with a 404.
     */
    var textModelId: String
        get() = prefs.getString(KEY_TEXT_MODEL, null)
            ?.takeIf { id -> agentModelOrNull(id) != null }
            ?: DEFAULT_TEXT_MODEL
        set(value) {
            prefs.edit().putString(KEY_TEXT_MODEL, value).apply()
        }

    /** Whether newly created tasks are handed to the agent (labeled "by-agent") by default. */
    var agentHandledByDefault: Boolean
        get() = prefs.getBoolean(KEY_AGENT_DEFAULT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AGENT_DEFAULT, value).apply()
        }

    private companion object {
        const val TAG = "MIA_SecretStore"
        const val PREFS_NAME = "mia_secrets"
        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_OPENROUTER_FALLBACK = "openrouter_fallback_api_key"
        const val KEY_MINIMAX = "minimax_api_key"
        const val KEY_TEXT_MODEL = "text_model_id"
        const val KEY_AGENT_DEFAULT = "agent_handled_by_default"

        /**
         * Opens the store, resetting it once if it can't be decrypted.
         *
         * [PREFS_NAME] holds both the secrets and the Tink keyset that protects them, and that
         * keyset is itself sealed with a Keystore key that never leaves the device. So the file
         * and the key can drift apart — a cloud backup or device-transfer restore brings the file
         * to a phone whose Keystore has a different key, and clearing the lock screen can drop
         * the key out from under a file that stays. Either way `create` fails on the keyset with
         * an AEADBadTagException, which used to take [ir.mahditavakoli.mia.MIAApplication] down
         * with it: an unrecoverable crash on every launch, on a store holding nothing that can't
         * be typed in again.
         *
         * Wiping the file and the stale Keystore alias makes the next attempt build a fresh pair.
         * The runtime keys are lost, but the app starts, falls back to its BuildConfig defaults,
         * and Settings can take them again.
         */
        fun openPrefs(appContext: Context): SharedPreferences = try {
            createPrefs(appContext)
        } catch (e: GeneralSecurityException) {
            resetAndCreatePrefs(appContext, e)
        } catch (e: IOException) {
            resetAndCreatePrefs(appContext, e)
        }

        fun createPrefs(appContext: Context): SharedPreferences =
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

        /**
         * Second and last attempt: if this one throws too, the Keystore is broken in a way we
         * can't undo by deleting our own state, and crashing beats pretending secrets are stored.
         */
        fun resetAndCreatePrefs(appContext: Context, cause: Exception): SharedPreferences {
            Log.w(TAG, "Secret store unreadable; resetting it. Saved API keys are lost.", cause)
            appContext.deleteSharedPreferences(PREFS_NAME)
            deleteMasterKey()
            return createPrefs(appContext)
        }

        /**
         * Drops the Keystore key behind [MasterKey], so the retry generates a new one. Failing to
         * delete it is not fatal on its own: with the file gone the retry can still succeed by
         * re-sealing a new keyset under the existing key.
         */
        fun deleteMasterKey() {
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE)
                    .apply { load(null) }
                    .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Could not delete the master key", e)
            } catch (e: IOException) {
                Log.w(TAG, "Could not delete the master key", e)
            }
        }

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
