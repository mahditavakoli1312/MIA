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

/**
 * "2026-08-28T09:15:00Z" → epoch millis, or 0 when the input isn't in that shape.
 *
 * Hand-rolled for the same reason [formatIssueTimestamp] is: `java.time` needs core-library
 * desugaring this module doesn't enable, and `SimpleDateFormat` would need a fixed UTC zone set on
 * every call to avoid reading the timestamp in the device's own zone. GitHub only ever sends this
 * one format, so parsing it is a dozen lines of arithmetic with no ambiguity in it.
 *
 * 0 rather than null: every caller buckets by time and treats 0 as "no date", so a nullable return
 * would only move the same check to five call sites.
 */
fun epochMillisFromIso(iso: String?): Long {
    val text = iso?.trim().orEmpty()
    if (text.length < 20 || text[4] != '-' || text[7] != '-' || text[10] != 'T') return 0L
    val year = text.substring(0, 4).toIntOrNull() ?: return 0L
    val month = text.substring(5, 7).toIntOrNull() ?: return 0L
    val day = text.substring(8, 10).toIntOrNull() ?: return 0L
    val hour = text.substring(11, 13).toIntOrNull() ?: return 0L
    val minute = text.substring(14, 16).toIntOrNull() ?: return 0L
    val second = text.substring(17, 19).toIntOrNull() ?: return 0L
    if (month !in 1..12 || day !in 1..31) return 0L
    return (daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second) * 1000L
}

/**
 * Days since 1970-01-01 for a proleptic Gregorian date — Howard Hinnant's `days_from_civil`.
 *
 * It shifts the year to start in March so that the leap day lands at the end of the 400-year cycle,
 * which is what removes every special case from the arithmetic.
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400                                    // 0..399
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1  // 0..365
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear         // 0..146096
    return era * 146_097L + dayOfEra - 719_468L
}
