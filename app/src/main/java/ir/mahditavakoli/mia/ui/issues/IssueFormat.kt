package ir.mahditavakoli.mia.ui.issues

/**
 * GitHub timestamps ("2026-08-28T09:15:00Z") shortened to "2026-08-28 09:15".
 *
 * Deliberately string surgery rather than date parsing: `java.time` needs core-library
 * desugaring this module doesn't enable, and `SimpleDateFormat` would re-render the same UTC
 * instant in the device's locale and time zone — turning an exact server timestamp into
 * something that silently disagrees with what GitHub's own issue page shows. Input that isn't
 * in the expected shape is passed through untouched.
 */
fun formatIssueTimestamp(iso: String?): String {
    val text = iso?.trim().orEmpty()
    if (text.length < 16 || text[10] != 'T') return text
    return text.substring(0, 10) + " " + text.substring(11, 16)
}
