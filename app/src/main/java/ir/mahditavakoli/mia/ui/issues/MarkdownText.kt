package ir.mahditavakoli.mia.ui.issues

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Renders issue Markdown — see [parseMarkdown] for what is supported and why nothing is hidden.
 *
 * Two things here exist purely because MIA is an RTL app showing agent output:
 *  - Paragraphs use `TextDirection.Content`, so a Persian sentence lays out RTL and an English one
 *    LTR, per paragraph, instead of every line inheriting the app's direction.
 *  - Code blocks and tables are forced LTR and scroll horizontally inside themselves. A Kotlin
 *    snippet mirrored by an RTL root is not just ugly, it is wrong — the indentation ends up on the
 *    wrong side and the whole thing stops being copyable by eye.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    // Parsing is pure and cheap, but it runs on every recomposition of a screen that scrolls, so
    // it is keyed on the text rather than repeated.
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(BLOCK_GAP))
            MarkdownBlock(block, style)
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: TextStyle) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = block.spans.toAnnotatedString(),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface
        )

        is MdBlock.Paragraph -> Text(
            text = block.spans.toAnnotatedString(),
            style = style.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface
        )

        is MdBlock.CodeBlock -> CodeBlock(block)

        is MdBlock.ListBlock -> Column {
            block.items.forEach { item -> ListItemRow(item, block.ordered, style) }
        }

        is MdBlock.Table -> TableBlock(block, style)

        is MdBlock.Quote -> Row {
            Box(
                Modifier
                    .width(3.dp)
                    .height(QUOTE_BAR_MIN)
                    .background(MaterialTheme.colorScheme.outline)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                block.blocks.forEachIndexed { index, inner ->
                    if (index > 0) Spacer(Modifier.height(BLOCK_GAP))
                    MarkdownBlock(inner, style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }

        MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * A fenced block: monospace, its own surface, LTR, and horizontally scrollable.
 *
 * Not wrapped: a wrapped line of code reads as two statements, and guessing where to break someone
 * else's source is worse than making them scroll.
 */
@Composable
private fun CodeBlock(block: MdBlock.CodeBlock) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            block.language?.let { language ->
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr
                    ),
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ListItemRow(item: MdListItem, ordered: Boolean, style: TextStyle) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (item.depth * 12).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        when {
            // A checkbox list is read-only here: ticking it would have to write to GitHub, and a
            // box that looks tappable and isn't would be worse than one that plainly isn't.
            item.checked != null -> Icon(
                imageVector = if (item.checked) {
                    Icons.Filled.CheckBox
                } else {
                    Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = if (item.checked) "انجام‌شده" else "انجام‌نشده",
                tint = if (item.checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp)
            )

            ordered -> Text(
                text = "${item.number ?: 1}.",
                style = style,
                color = MaterialTheme.colorScheme.secondary
            )

            else -> Text(text = "•", style = style, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.spans.toAnnotatedString(),
            style = style.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A table, LTR and scrolling inside its own box.
 *
 * Columns get a minimum width rather than a weight: the agents' tables are mostly one narrow
 * column of ticks and one wide column of prose, and equal weights would squeeze the prose into a
 * ribbon while the ticks got a third of the screen.
 */
@Composable
private fun TableBlock(block: MdBlock.Table, style: TextStyle) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            Column {
                TableRow(block.header, style, isHeader = true)
                block.rows.forEach { row ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    TableRow(row, style, isHeader = false)
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<MdSpan>>, style: TextStyle, isHeader: Boolean) {
    Row(
        Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        cells.forEach { cell ->
            Text(
                text = cell.toAnnotatedString(),
                style = style.copy(
                    fontWeight = if (isHeader) FontWeight.SemiBold else null,
                    textDirection = TextDirection.Content
                ),
                color = if (isHeader) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.widthIn(min = 48.dp, max = 240.dp)
            )
        }
    }
}

/**
 * Inline spans to styled text, with links that actually open.
 *
 * `withLink` (Compose 1.7) handles the click, the accessibility role and the press state, which is
 * three things a hand-rolled `ClickableText` gets subtly wrong.
 */
@Composable
private fun List<MdSpan>.toAnnotatedString(): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val baseColor = LocalTextStyle.current.color
    return buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            when (span) {
                is MdSpan.Plain -> append(span.text)

                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(span.text)
                }

                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(span.text)
                }

                is MdSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        color = baseColor
                    )
                ) {
                    append(span.text)
                }

                is MdSpan.Link -> withLink(
                    LinkAnnotation.Url(
                        url = span.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        )
                    )
                ) {
                    // A bare URL as its own link text is noise; the title carries the meaning.
                    append(span.text.ifBlank { span.url })
                }
            }
        }
    }
}

private val BLOCK_GAP = 8.dp
private val QUOTE_BAR_MIN = 20.dp
