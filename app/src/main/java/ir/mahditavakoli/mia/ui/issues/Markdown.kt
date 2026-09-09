package ir.mahditavakoli.mia.ui.issues

/**
 * A deliberately small Markdown parser for issue bodies and comments.
 *
 * Issue text used to be rendered as plain text, and that was the right call while the bodies were
 * prose. It stopped being right when the agents started answering in tables and fenced code: a
 * token report or a QC verdict rendered as literal pipes and backticks is unreadable.
 *
 * The one rule that shapes everything here: **nothing is ever hidden.** Anything the parser does
 * not recognise comes back as a paragraph holding its literal text, an unterminated code fence is
 * literal text, and a table whose rows don't line up is literal text. Half-parsing was the failure
 * plain text was chosen to avoid, so the parser fails towards showing too much rather than too
 * little.
 *
 * Pure functions over strings, so the interesting part is unit-tested without a device.
 */

/** One inline run of text. Nesting is deliberately not supported — see [parseInline]. */
sealed interface MdSpan {
    val text: String

    data class Plain(override val text: String) : MdSpan
    data class Bold(override val text: String) : MdSpan
    data class Italic(override val text: String) : MdSpan
    data class Code(override val text: String) : MdSpan
    data class Link(override val text: String, val url: String) : MdSpan
}

/** One item of a list, with its checkbox state when it has one. */
data class MdListItem(
    val spans: List<MdSpan>,
    /** Null for an ordinary bullet; true/false for `- [x]` / `- [ ]`. */
    val checked: Boolean? = null,
    /** Indentation depth, two spaces per level, so a nested list still reads as nested. */
    val depth: Int = 0,
    /** The number a numbered item carried, so "3." stays "3." and not "1.". */
    val number: Int? = null
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** A fenced block. [language] is whatever followed the opening fence, or null. */
    data class CodeBlock(val language: String?, val code: String) : MdBlock

    data class ListBlock(val items: List<MdListItem>, val ordered: Boolean) : MdBlock

    /** A GFM table. Every row is padded or trimmed to the header's width by the parser. */
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock

    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})\s*(\S+)?\s*$""")
private val BULLET = Regex("""^(\s*)([-*+])\s+(.*)$""")
private val ORDERED = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val CHECKBOX = Regex("""^\[([ xX])]\s+(.*)$""")
private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
private val TABLE_DIVIDER = Regex("""^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")

/**
 * Splits [markdown] into blocks.
 *
 * Written as an index walk rather than a fold because most blocks are multi-line and each one
 * decides for itself how far it extends — which is also what makes "give up and emit the literal
 * text" a one-line escape at every branch.
 */
fun parseMarkdown(markdown: String): List<MdBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        if (line.isBlank()) {
            i++
            continue
        }

        val fence = FENCE.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].takeIf { it.isNotBlank() }
            val body = mutableListOf<String>()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val candidate = lines[j].trim()
                if (candidate.length >= marker.length && candidate.all { it == marker[0] }) {
                    closed = true
                    break
                }
                body += lines[j]
                j++
            }
            if (closed) {
                blocks += MdBlock.CodeBlock(language, body.joinToString("\n"))
                i = j + 1
            } else {
                // Never swallow the rest of the document because someone forgot a closing fence.
                blocks += MdBlock.Paragraph(listOf(MdSpan.Plain(line)))
                i++
            }
            continue
        }

        if (RULE.matches(line)) {
            blocks += MdBlock.Rule
            i++
            continue
        }

        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            blocks += MdBlock.Heading(
                level = heading.groupValues[1].length,
                spans = parseInline(heading.groupValues[2])
            )
            i++
            continue
        }

        if (QUOTE.matches(line)) {
            val quoted = mutableListOf<String>()
            while (i < lines.size) {
                val match = QUOTE.matchEntire(lines[i]) ?: break
                quoted += match.groupValues[1]
                i++
            }
            blocks += MdBlock.Quote(parseMarkdown(quoted.joinToString("\n")))
            continue
        }

        // A table needs its divider on the very next line; without one these are just paragraphs
        // that happen to contain pipes, which is common in Persian prose about shell commands.
        if (line.contains('|') && TABLE_DIVIDER.matches(lines.getOrNull(i + 1).orEmpty())) {
            val header = splitRow(line)
            val rows = mutableListOf<List<List<MdSpan>>>()
            var j = i + 2
            while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                val cells = splitRow(lines[j])
                // Pad short rows and drop the overflow of long ones, so the grid stays a grid.
                rows += List(header.size) { index -> cells.getOrElse(index) { emptyList() } }
                j++
            }
            blocks += MdBlock.Table(header, rows)
            i = j
            continue
        }

        if (BULLET.matches(line) || ORDERED.matches(line)) {
            val ordered = ORDERED.matches(line) && !BULLET.matches(line)
            val items = mutableListOf<MdListItem>()
            while (i < lines.size) {
                val bullet = BULLET.matchEntire(lines[i])
                val numbered = ORDERED.matchEntire(lines[i])
                val match = bullet ?: numbered ?: break
                // A list ends where the marker style changes: two adjacent lists of different
                // kinds are two blocks, not one with mixed markers.
                if ((numbered != null && bullet == null) != ordered) break
                val indent = match.groupValues[1].length
                val rest = match.groupValues[3]
                val checkbox = CHECKBOX.matchEntire(rest)
                items += MdListItem(
                    spans = parseInline(checkbox?.groupValues?.get(2) ?: rest),
                    checked = checkbox?.groupValues?.get(1)?.let { it == "x" || it == "X" },
                    depth = indent / 2,
                    number = numbered?.groupValues?.get(2)?.toIntOrNull()
                )
                i++
            }
            blocks += MdBlock.ListBlock(items, ordered)
            continue
        }

        // Paragraph: everything up to a blank line or the start of another block. Single newlines
        // are kept, because that is how GitHub renders them in an issue body and how the person
        // who wrote the Persian text meant them.
        val paragraph = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i], lines.getOrNull(i + 1))) {
            paragraph += lines[i]
            i++
        }
        if (paragraph.isEmpty()) {
            // Defensive: a line that startsBlock() claims but no branch above consumed would
            // otherwise spin forever. Emit it literally and move on.
            blocks += MdBlock.Paragraph(listOf(MdSpan.Plain(lines[i])))
            i++
            continue
        }
        blocks += MdBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
    }

    return blocks
}

/** Whether [line] begins a block other than a paragraph, so a paragraph must stop before it. */
private fun startsBlock(line: String, next: String?): Boolean =
    FENCE.matchEntire(line) != null ||
        RULE.matches(line) ||
        HEADING.matchEntire(line) != null ||
        QUOTE.matches(line) ||
        BULLET.matches(line) ||
        ORDERED.matches(line) ||
        (line.contains('|') && TABLE_DIVIDER.matches(next.orEmpty()))

/** One table row into cells, without the leading/trailing pipes GFM allows. */
private fun splitRow(line: String): List<List<MdSpan>> =
    line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        // An escaped pipe is content, not a cell boundary — the QC table escapes them for exactly
        // this reason.
        .split(Regex("""(?<!\\)\|"""))
        .map { parseInline(it.trim().replace("\\|", "|")) }

/**
 * Splits one line of text into inline runs: `code`, **bold**, *italic* and [links](url).
 *
 * Nesting is not supported, on purpose. Supporting bold-inside-a-link means a tree, a tree means a
 * recursive renderer, and the whole point of this file is to stay small enough to trust. A run
 * that isn't closed on the same line is left as the literal characters the author typed.
 */
fun parseInline(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val plain = StringBuilder()

    fun flush() {
        if (plain.isNotEmpty()) {
            spans += MdSpan.Plain(plain.toString())
            plain.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)

        // Code first: a backtick run wins over everything inside it, as in real Markdown.
        if (text[i] == '`') {
            val close = text.indexOf('`', startIndex = i + 1)
            if (close > i + 1) {
                flush()
                spans += MdSpan.Code(text.substring(i + 1, close))
                i = close + 1
                continue
            }
        }

        if (text[i] == '[') {
            val link = LINK.find(rest)
            if (link != null && link.range.first == 0) {
                flush()
                spans += MdSpan.Link(text = link.groupValues[1], url = link.groupValues[2])
                i += link.value.length
                continue
            }
        }

        val bold = BOLD.find(rest)?.takeIf { it.range.first == 0 }
        if (bold != null) {
            flush()
            spans += MdSpan.Bold(bold.groupValues[1].ifEmpty { bold.groupValues[2] })
            i += bold.value.length
            continue
        }

        val italic = ITALIC.find(rest)?.takeIf { it.range.first == 0 }
        if (italic != null) {
            flush()
            spans += MdSpan.Italic(italic.groupValues[1].ifEmpty { italic.groupValues[2] })
            i += italic.value.length
            continue
        }

        plain.append(text[i])
        i++
    }
    flush()
    return spans
}

private val LINK = Regex("""^\[([^]]*)]\(([^)\s]+)\)""")
private val BOLD = Regex("""^(?:\*\*(.+?)\*\*|__(.+?)__)""")
private val ITALIC = Regex("""^(?:\*(.+?)\*|_(.+?)_)""")

/** The literal characters a span was written with, for callers that want the source back. */
fun List<MdSpan>.plainText(): String = joinToString("") { it.text }
