package ir.mahditavakoli.mia.text

/**
 * Deterministic, on-device Persian text handling — the first (free, instant) stage of the
 * typed-command pipeline, before any model is called.
 *
 * Persian text arriving from a soft keyboard is orthographically noisy in ways that carry no
 * meaning: Arabic ي/ك/ة instead of the Persian letters, Arabic-Indic digits, stray diacritics
 * and tatweel, and inconsistent spacing/نیم‌فاصله. Normalizing that here means the model never
 * spends attention (or tokens) on it, and means two spellings of the same project name compare
 * equal without a model call at all.
 *
 * Two levels, deliberately separate:
 *  - [normalize] cleans text a human will still read (it keeps words, spacing and ZWNJ).
 *  - [fold] produces a comparison key only (it throws spacing away entirely).
 */
object PersianText {

    /** Zero-width non-joiner (نیم‌فاصله): meaningful inside a word, noise anywhere else. */
    private const val ZWNJ = '\u200C'

    /** Zero-width space/joiner and the bidi marks: invisible, and never meaningful for us. */
    private val INVISIBLES = Regex("[\u200B\u200D\u200E\u200F\u2060\uFEFF]")

    /** Arabic harakat (fatha…sukun) and the superscript alef — decorative in Persian prose. */
    private val DIACRITICS = Regex("[\u064B-\u0652\u0670]")

    /** Kashida/tatweel: a typographic stretch character with no phonetic value. */
    private const val TATWEEL = '\u0640'

    private const val PERSIAN_ZERO = '\u06F0' // ۰
    private const val ARABIC_ZERO = '\u0660'  // ٠

    /** A ZWNJ that has whitespace on either side is just a space that was typed twice. */
    private val LOOSE_ZWNJ = Regex(" $ZWNJ ?| ?$ZWNJ ")

    /**
     * Canonical form of user-typed Persian, safe both to show back to the user and to send to a
     * model: Persian letter forms, ASCII digits, no diacritics/tatweel/invisibles, single spaces,
     * and at most one ZWNJ in a row. Word boundaries and punctuation survive as typed.
     */
    fun normalize(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when {
                ch == TATWEEL -> Unit
                ch in PERSIAN_ZERO..PERSIAN_ZERO + 9 -> append('0' + (ch - PERSIAN_ZERO))
                ch in ARABIC_ZERO..ARABIC_ZERO + 9 -> append('0' + (ch - ARABIC_ZERO))
                else -> append(canonicalLetter(ch))
            }
        }
    }
        .replace(DIACRITICS, "")
        .replace(INVISIBLES, "")
        .replace(Regex("$ZWNJ+"), ZWNJ.toString())
        // Any run of whitespace (including newlines pasted in) becomes one space, then the
        // ZWNJs left stranded beside one are folded into that space.
        .replace(Regex("\\s+"), " ")
        .replace(LOOSE_ZWNJ, " ")
        .trim { it.isWhitespace() || it == ZWNJ }

    /**
     * A matching key: [normalize] plus lowercasing and the removal of every space and ZWNJ, so
     * "برنامه ریزی", "برنامه‌ریزی" and "برنامهریزی" all collapse to the same string. Only ever
     * used to decide whether two names refer to the same thing — never shown to the user.
     */
    fun fold(value: String): String = normalize(value)
        .replace(ZWNJ.toString(), "")
        .replace(Regex("\\s+"), "")
        .lowercase()

    /** Arabic letter forms that keyboards emit where Persian has its own letter. */
    private fun canonicalLetter(ch: Char): Char = when (ch) {
        '\u064A' -> '\u06CC' // Arabic Yeh -> Persian Yeh (ي -> ی)
        '\u0643' -> '\u06A9' // Arabic Kaf -> Persian Keheh (ك -> ک)
        '\u0629' -> '\u0647' // Teh Marbuta -> Heh (ة -> ه)
        '\u06C0' -> '\u0647' // Heh with Yeh above -> Heh (ۀ -> ه)
        else -> ch
    }
}
