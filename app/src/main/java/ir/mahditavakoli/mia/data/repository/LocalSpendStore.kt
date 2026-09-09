package ir.mahditavakoli.mia.data.repository

import android.content.Context
import ir.mahditavakoli.mia.data.model.SpendEntry
import ir.mahditavakoli.mia.data.model.SpendRole
import ir.mahditavakoli.mia.data.model.TokenUsage

/**
 * MIA's own token spend, kept on the device.
 *
 * The agents' spend can always be read back off GitHub — a commit trailer or a comment is a durable
 * record. The app's own understanding calls have no such record: the footer on an issue names them,
 * but a command that created no issue (a rename, a deletion, a command that failed) leaves no trace
 * anywhere. Without this store the spend screen would under-report MIA's own share, which is the
 * one share the user is billed for directly on their own Gemini key.
 *
 * Plain SharedPreferences, not [ir.mahditavakoli.mia.security.SecretStore]: there is nothing secret
 * about a token count, and encrypting it would only make it harder to inspect.
 *
 * Bounded by [MAX_ENTRIES]. A ledger that grows forever on a phone is a bug, and the screen only
 * ever looks at the last few weeks.
 */
class LocalSpendStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mia_local_spend", Context.MODE_PRIVATE)

    /** Records one classification call. Best-effort: accounting must never fail a command. */
    fun record(usage: TokenUsage, atMillis: Long = System.currentTimeMillis()) {
        if (usage.totalTokens <= 0) return
        runCatching {
            val line = listOf(
                atMillis.toString(),
                usage.totalTokens.toString(),
                // The model id is the only free-form field, and a "|" inside it would corrupt the
                // row — so it is the field that gets sanitised rather than the format that grows.
                usage.model.replace('|', '/')
            ).joinToString("|")
            val kept = (read() + line).takeLast(MAX_ENTRIES)
            prefs.edit().putStringSet(KEY_ENTRIES, kept.toSet()).apply()
        }
    }

    /** Every recorded call, as spend entries. Unparseable rows are skipped, never guessed at. */
    fun entries(): List<SpendEntry> = read().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val tokens = parts[1].toIntOrNull() ?: return@mapNotNull null
        SpendEntry(
            role = SpendRole.MIA,
            model = parts[2].ifBlank { "نامشخص" },
            tokens = tokens,
            // Gemini's own quota is not billed per call through anything MIA can see, so the cost
            // is genuinely unknown here rather than zero.
            costUsd = null,
            atMillis = at
        )
    }.sortedBy { it.atMillis }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /**
     * A `StringSet` rather than a list, because SharedPreferences has no ordered collection — the
     * timestamp is the first field precisely so the rows can be re-ordered on the way out.
     */
    private fun read(): List<String> =
        prefs.getStringSet(KEY_ENTRIES, emptySet()).orEmpty().sorted()

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 500
    }
}
