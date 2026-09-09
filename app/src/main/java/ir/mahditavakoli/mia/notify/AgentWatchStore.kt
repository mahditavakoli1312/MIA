package ir.mahditavakoli.mia.notify

import android.content.Context
import android.content.SharedPreferences

/**
 * What the background watcher remembers between runs, per repo: when it last looked, and which
 * issues it has already told the user about.
 *
 * Plain [SharedPreferences] rather than the encrypted store
 * ([ir.mahditavakoli.mia.security.SecretStore]) — this holds timestamps and issue numbers, not
 * secrets, and paying Tink's key-unwrap cost on a fifteen-minute timer to protect "repo X was
 * last checked at Y" would be ceremony for nothing.
 *
 * The notified set is what keeps a finished issue from being announced twice. The watermark
 * alone cannot do it: GitHub's `since` is "updated at or after", so an issue that is already
 * closed and then gets one more comment comes back in the very next sweep.
 */
class AgentWatchStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When this repo was last swept (ISO-8601 UTC), or null if it never has been. */
    fun lastCheckedAt(repo: String): String? = prefs.getString(keyChecked(repo), null)

    fun setLastCheckedAt(repo: String, iso: String) {
        prefs.edit().putString(keyChecked(repo), iso).apply()
    }

    fun hasNotified(repo: String, issueNumber: Int): Boolean =
        notified(repo).contains(issueNumber.toString())

    /**
     * Records that [issueNumbers] have been announced for this repo.
     *
     * The set is trimmed to [MAX_REMEMBERED] most-recent entries: it exists only to suppress a
     * duplicate notification for work that just finished, and an unbounded set on a long-lived
     * repo would grow forever for no benefit. Issue numbers rise monotonically, so "most recent"
     * is just the largest ones.
     */
    fun markNotified(repo: String, issueNumbers: Collection<Int>) {
        if (issueNumbers.isEmpty()) return
        val merged = notified(repo) + issueNumbers.map { it.toString() }
        val trimmed = merged
            .sortedByDescending { it.toIntOrNull() ?: 0 }
            .take(MAX_REMEMBERED)
            .toSet()
        prefs.edit().putStringSet(keyNotified(repo), trimmed).apply()
    }

    /** Forgets everything — used when the GitHub token goes away, so a new one starts clean. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    // getStringSet's own docs forbid mutating the returned set, so copy it on the way out.
    private fun notified(repo: String): Set<String> =
        prefs.getStringSet(keyNotified(repo), emptySet())?.toSet().orEmpty()

    private fun keyChecked(repo: String) = "checked_$repo"

    private fun keyNotified(repo: String) = "notified_$repo"

    private companion object {
        const val PREFS_NAME = "mia_agent_watch"
        const val MAX_REMEMBERED = 200
    }
}
