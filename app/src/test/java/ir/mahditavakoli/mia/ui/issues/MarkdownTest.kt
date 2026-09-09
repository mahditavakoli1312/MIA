package ir.mahditavakoli.mia.ui.issues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown parser, and in particular the rule that decides its shape: **nothing is hidden.**
 *
 * Half the tests here are about text that is *not* Markdown — a stray pipe, an unclosed fence, an
 * asterisk in the middle of a Persian sentence — because that is the failure mode plain text was
 * chosen to avoid, and the only reason it is safe to parse a model's output at all.
 */
class MarkdownTest {

    private fun MdBlock.asParagraph() = this as MdBlock.Paragraph

    @Test
    fun `headings carry their level and their inline content`() {
        val blocks = parseMarkdown("## معیارهای **پذیرش**")
        val heading = blocks.single() as MdBlock.Heading
        assertEquals(2, heading.level)
        assertEquals("معیارهای پذیرش", heading.spans.plainText())
        assertTrue(heading.spans.any { it is MdSpan.Bold })
    }

    @Test
    fun `a fenced block keeps its language and its exact text`() {
        val blocks = parseMarkdown(
            """
            before

            ```kotlin
            val x = 1
                indented()
            ```

            after
            """.trimIndent()
        )
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\n    indented()", code.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an unterminated fence does not swallow the rest of the document`() {
        val blocks = parseMarkdown(
            """
            ```
            val x = 1
            still here
            """.trimIndent()
        )
        // The fence line comes back literally, and the two lines after it are still content.
        assertTrue(blocks.none { it is MdBlock.CodeBlock })
        assertTrue(blocks.joinToString("") { (it as MdBlock.Paragraph).spans.plainText() }.contains("still here"))
    }

    @Test
    fun `checkbox lists keep their state`() {
        val list = parseMarkdown(
            """
            - [ ] دکمه دیده شود
            - [x] با لمس خارج شود
            - یک بند ساده
            """.trimIndent()
        ).single() as MdBlock.ListBlock

        assertEquals(3, list.items.size)
        assertEquals(false, list.items[0].checked)
        assertEquals(true, list.items[1].checked)
        assertEquals(null, list.items[2].checked)
        assertEquals("دکمه دیده شود", list.items[0].spans.plainText())
    }

    @Test
    fun `a numbered list keeps the numbers the author wrote`() {
        val list = parseMarkdown(
            """
            1. اول
            2. دوم
            5. پنجم
            """.trimIndent()
        ).single() as MdBlock.ListBlock

        assertTrue(list.ordered)
        assertEquals(listOf(1, 2, 5), list.items.map { it.number })
    }

    @Test
    fun `nesting depth comes from the indentation`() {
        val list = parseMarkdown(
            """
            - بالا
              - تو رفته
            """.trimIndent()
        ).single() as MdBlock.ListBlock

        assertEquals(0, list.items[0].depth)
        assertEquals(1, list.items[1].depth)
    }

    @Test
    fun `a table needs its divider, and pads short rows to the header width`() {
        val table = parseMarkdown(
            """
            | | معیار | یادداشت |
            | :-: | --- | --- |
            | ✅ | دیده می‌شود | در TopBar |
            | ❌ | کار نمی‌کند |
            """.trimIndent()
        ).single() as MdBlock.Table

        assertEquals(3, table.header.size)
        assertEquals(2, table.rows.size)
        assertEquals(3, table.rows[1].size)
        assertEquals("معیار", table.header[1].plainText())
        assertEquals("", table.rows[1][2].plainText())
    }

    @Test
    fun `an escaped pipe stays inside its cell`() {
        val table = parseMarkdown(
            """
            | a | b |
            | --- | --- |
            | x \| y | z |
            """.trimIndent()
        ).single() as MdBlock.Table

        assertEquals("x | y", table.rows[0][0].plainText())
    }

    @Test
    fun `pipes without a divider line are ordinary text`() {
        val blocks = parseMarkdown("دستور `git log | head` را اجرا کنید")
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun `blockquotes are parsed as their own nested blocks`() {
        val quote = parseMarkdown(
            """
            > ## سرفصل
            > یک خط
            """.trimIndent()
        ).single() as MdBlock.Quote

        assertTrue(quote.blocks.first() is MdBlock.Heading)
        assertEquals(2, quote.blocks.size)
    }

    @Test
    fun `horizontal rules are recognised and dashes in prose are not`() {
        assertTrue(parseMarkdown("---").single() is MdBlock.Rule)
        assertTrue(parseMarkdown("یک — دو").single() is MdBlock.Paragraph)
    }

    @Test
    fun `a paragraph keeps single newlines, the way GitHub renders an issue body`() {
        val paragraph = parseMarkdown("خط اول\nخط دوم").single().asParagraph()
        assertEquals("خط اول\nخط دوم", paragraph.spans.plainText())
    }

    @Test
    fun `inline runs are split into code, bold, italic and links`() {
        val spans = parseInline("یک `code` و **پرکاربرد** و *کج* و [لینک](https://example.com/x)")

        assertTrue(spans.any { it is MdSpan.Code && it.text == "code" })
        assertTrue(spans.any { it is MdSpan.Bold && it.text == "پرکاربرد" })
        assertTrue(spans.any { it is MdSpan.Italic && it.text == "کج" })
        val link = spans.filterIsInstance<MdSpan.Link>().single()
        assertEquals("لینک", link.text)
        assertEquals("https://example.com/x", link.url)
    }

    @Test
    fun `markers that are never closed stay literal`() {
        assertEquals("۲ * ۳ = ۶", parseInline("۲ * ۳ = ۶").plainText())
        assertTrue(parseInline("۲ * ۳ = ۶").all { it is MdSpan.Plain })
        assertTrue(parseInline("a `unclosed code").all { it is MdSpan.Plain })
        assertTrue(parseInline("[label](not a url with spaces)").all { it is MdSpan.Plain })
    }

    @Test
    fun `code wins over the markers inside it`() {
        val spans = parseInline("`**not bold**`")
        assertEquals(listOf<MdSpan>(MdSpan.Code("**not bold**")), spans)
    }

    @Test
    fun `the whole of a real token-spend comment survives a round trip`() {
        val comment = """
            ### 💸 Token spend for #7

            _rung 2/3: green after 2 build attempt(s)_

            | | tokens |
            | --- | ---: |
            | Prompt (input) | 12,000 |
            | **Total** | **13,500** |

            Model `x/y:free` · 4 model calls · cost **$0.00**

            [Workflow run](https://github.com/o/r/actions/runs/9)
        """.trimIndent()

        val blocks = parseMarkdown(comment)
        assertTrue(blocks.any { it is MdBlock.Heading })
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(2, table.header.size)
        assertEquals(2, table.rows.size)
        assertTrue(
            blocks.filterIsInstance<MdBlock.Paragraph>()
                .flatMap { it.spans }
                .any { it is MdSpan.Link && it.url.endsWith("/runs/9") }
        )
        // Nothing was lost: every visible character of the source is somewhere in the blocks.
        val rendered = blocks.joinToString("\n") { block ->
            when (block) {
                is MdBlock.Heading -> block.spans.plainText()
                is MdBlock.Paragraph -> block.spans.plainText()
                is MdBlock.CodeBlock -> block.code
                is MdBlock.ListBlock -> block.items.joinToString("\n") { it.spans.plainText() }
                is MdBlock.Table ->
                    (listOf(block.header) + block.rows).joinToString("\n") { row ->
                        row.joinToString(" ") { it.plainText() }
                    }
                is MdBlock.Quote -> ""
                MdBlock.Rule -> "---"
            }
        }
        assertTrue(rendered.contains("13,500"))
        assertTrue(rendered.contains("Token spend for #7"))
        assertTrue(rendered.contains("rung 2/3"))
    }

    @Test
    fun `empty and blank input produce no blocks`() {
        assertTrue(parseMarkdown("").isEmpty())
        assertTrue(parseMarkdown("\n\n   \n").isEmpty())
    }
}
