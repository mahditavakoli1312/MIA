package ir.mahditavakoli.mia.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The on-device stage of the typed-command pipeline. Everything here happens before a single
 * token is spent, so these cases are the ones the model should never have to think about.
 */
class PersianTextTest {

    @Test
    fun `normalize converts arabic letter forms to persian`() {
        // What an Arabic-layout keyboard emits for "طراحی صفحه ورود".
        assertEquals("طراحی صفحه ورود", PersianText.normalize("طراحي صفحة ورود"))
        assertEquals("کتاب", PersianText.normalize("كتاب"))
    }

    @Test
    fun `normalize converts persian and arabic digits to ascii`() {
        assertEquals("تا 1405/03/12", PersianText.normalize("تا ۱۴۰۵/۰۳/۱۲"))
        assertEquals("2026", PersianText.normalize("٢٠٢٦"))
    }

    @Test
    fun `normalize collapses whitespace and strips decoration`() {
        assertEquals(
            "یک پروژه جدید بساز",
            PersianText.normalize("  یک   پروژه\n\nجدید\tبساز  ")
        )
        // Tatweel and harakat carry no meaning in a command.
        assertEquals("سلام", PersianText.normalize("سلامـــً"))
    }

    @Test
    fun `normalize keeps a meaningful zwnj but drops a stranded one`() {
        // Inside a word the نیم‌فاصله is part of the spelling.
        assertEquals("می‌روم", PersianText.normalize("می‌روم"))
        // Next to a space it is just a second space.
        assertEquals("خانه جدید", PersianText.normalize("خانه ‌ جدید"))
        assertEquals("خانه", PersianText.normalize("‌خانه‌"))
    }

    @Test
    fun `fold makes spacing variants of the same name compare equal`() {
        val variants = listOf("برنامه ریزی", "برنامه‌ریزی", "برنامهریزی", " برنامه  ریزی ")
        val folded = variants.map { PersianText.fold(it) }.distinct()
        assertEquals(1, folded.size)
    }

    @Test
    fun `fold ignores letter form and case differences`() {
        assertEquals(PersianText.fold("وبسايت"), PersianText.fold("وبسایت"))
        assertEquals(PersianText.fold("MIA App"), PersianText.fold("mia app"))
    }
}
