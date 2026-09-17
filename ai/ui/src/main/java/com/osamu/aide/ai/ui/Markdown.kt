package com.osamu.aide.ai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * The Markdown a model actually emits, parsed into blocks.
 *
 * **Pure, and separate from the renderer, so it can be tested.** The rendering
 * is Compose and needs a device; the parsing is where the bugs are and needs
 * nothing. Every case below was chosen by reading what the providers send: a
 * table of options, a nested list under a numbered step, a `---` between
 * sections, `[text](url)` links to documentation.
 *
 * **Streaming shapes the design.** This runs again on every token, so it does
 * one pass and no backtracking, and -- more visibly -- it must produce
 * *sensible* output for a half-written document. An unterminated fence is a
 * code block, not a paragraph beginning with three backticks, or every answer
 * containing code would flash its markup before settling.
 */
internal sealed interface MarkdownBlock {
    data class Code(val code: String, val language: String?, val complete: Boolean = true) : MarkdownBlock

    data class Header(val text: String, val level: Int) : MarkdownBlock

    /**
     * A whole list, not one item.
     *
     * **Grouped on purpose.** When each item was its own block, the gap between
     * two bullets was the gap between two paragraphs, so a five-item list read
     * as five separate thoughts. Grouping also makes nesting expressible, which
     * per-item blocks could not do at all.
     */
    data class ListBlock(val items: List<Item>, val ordered: Boolean) : MarkdownBlock {
        data class Item(
            val text: String,
            /** Nesting depth, already normalised from spaces or tabs. */
            val depth: Int,
            val marker: String,
            /** Set for `- [ ]` and `- [x]`; null for an ordinary item. */
            val checked: Boolean? = null,
        )
    }

    data class Quote(val text: String) : MarkdownBlock

    /**
     * A pipe table.
     *
     * Ragged rows are kept as they arrive rather than padded here: the renderer
     * knows the column count and can fill, and a row that is short *because it
     * is still streaming* should not gain empty cells that then shift.
     */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<Alignment>,
    ) : MarkdownBlock {
        enum class Alignment { START, CENTER, END }
    }

    data object Rule : MarkdownBlock

    data class Paragraph(val text: String) : MarkdownBlock
}

private val NUMBERED = Regex("^(\\d{1,9})[.)]\\s+(.*)")
private val BULLET = Regex("^([-*+])\\s+(.*)")
private val TASK = Regex("^\\[([ xX])]\\s+(.*)")
private val RULE = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val TABLE_DIVIDER = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val trimmed = raw.trim()

        // Fenced code. Checked first: everything inside a fence is literal, and
        // a line of dashes in a diff would otherwise become a rule.
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            // Only the leading word-ish characters: a 1.5B emitted ```json{ on
            // the phone, and the label rendered as "json{".
            val language = trimmed.drop(3).trim()
                .takeWhile { it.isLetterOrDigit() || it == '+' || it == '#' || it == '-' }
                .ifBlank { null }
            val body = mutableListOf<String>()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trim().startsWith(fence)) {
                    closed = true
                    i++
                    break
                }
                body += lines[i]
                i++
            }
            blocks += MarkdownBlock.Code(body.joinToString("\n"), language, complete = closed)
            continue
        }

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (RULE.matches(trimmed)) {
            blocks += MarkdownBlock.Rule
            i++
            continue
        }

        // ATX heading. `#hashtag` is not a heading -- the space is required, and
        // models write hashtags.
        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            val rest = trimmed.drop(level)
            if (level in 1..6 && rest.startsWith(" ")) {
                blocks += MarkdownBlock.Header(rest.trim(), level)
                i++
                continue
            }
        }

        // A table needs its divider row to be a table at all, so both lines are
        // inspected before committing. A single `| a | b |` line is a paragraph.
        if (trimmed.startsWith("|") && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1].trim())) {
            val header = splitRow(trimmed)
            val alignments = splitRow(lines[i + 1].trim()).map { cell ->
                val start = cell.startsWith(":")
                val end = cell.endsWith(":")
                when {
                    start && end -> MarkdownBlock.Table.Alignment.CENTER
                    end -> MarkdownBlock.Table.Alignment.END
                    else -> MarkdownBlock.Table.Alignment.START
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows += splitRow(lines[i].trim())
                i++
            }
            blocks += MarkdownBlock.Table(header, rows, alignments)
            continue
        }

        val listItem = listItemOf(raw)
        if (listItem != null) {
            val items = mutableListOf<MarkdownBlock.ListBlock.Item>()
            val ordered = listItem.second
            while (i < lines.size) {
                val parsed = listItemOf(lines[i]) ?: break
                // A bulleted list followed immediately by a numbered one is two
                // lists, and rendering them as one would number the bullets.
                if (parsed.second != ordered) break
                items += parsed.first
                i++
                // A continuation line -- indented, not a new item -- belongs to
                // the item above it. Models wrap long bullets this way.
                while (i < lines.size &&
                    lines[i].isNotBlank() &&
                    lines[i].first().isWhitespace() &&
                    listItemOf(lines[i]) == null
                ) {
                    val last = items.removeAt(items.lastIndex)
                    items += last.copy(text = last.text + " " + lines[i].trim())
                    i++
                }
            }
            blocks += MarkdownBlock.ListBlock(items, ordered)
            continue
        }

        if (trimmed.startsWith(">")) {
            val quoted = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoted += lines[i].trim().removePrefix(">").trim()
                i++
            }
            blocks += MarkdownBlock.Quote(quoted.joinToString(" ").trim())
            continue
        }

        // Paragraph: consecutive lines until something else starts. Joined with
        // newlines rather than spaces, because a model that broke a line
        // usually meant it.
        val paragraph = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty() ||
                t.startsWith("```") || t.startsWith("~~~") ||
                RULE.matches(t) ||
                (t.startsWith("#") && t.drop(t.takeWhile { it == '#' }.length).startsWith(" ")) ||
                t.startsWith(">") ||
                listItemOf(line) != null ||
                (t.startsWith("|") && i + 1 < lines.size && TABLE_DIVIDER.matches(lines[i + 1].trim()))
            ) {
                break
            }
            paragraph += line
            i++
        }
        if (paragraph.isEmpty()) {
            // Nothing matched and nothing consumed: take the line rather than
            // spin. An infinite loop here hangs the panel.
            paragraph += raw
            i++
        }
        blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n").trim())
    }

    return blocks
}

/** The item and whether it is ordered, or null when this line is not one. */
private fun listItemOf(raw: String): Pair<MarkdownBlock.ListBlock.Item, Boolean>? {
    val indent = raw.takeWhile { it == ' ' || it == '\t' }
        .sumOf { if (it == '\t') TAB_WIDTH else 1 }
    val trimmed = raw.trim()

    BULLET.find(trimmed)?.let { match ->
        val body = match.groupValues[2]
        val task = TASK.find(body)
        return MarkdownBlock.ListBlock.Item(
            text = task?.groupValues?.get(2) ?: body,
            depth = indent / INDENT_WIDTH,
            marker = match.groupValues[1],
            checked = task?.groupValues?.get(1)?.let { it == "x" || it == "X" },
        ) to false
    }
    NUMBERED.find(trimmed)?.let { match ->
        return MarkdownBlock.ListBlock.Item(
            text = match.groupValues[2],
            depth = indent / INDENT_WIDTH,
            marker = match.groupValues[1],
        ) to true
    }
    return null
}

/**
 * Cells of one table row.
 *
 * Escaped pipes are not handled, and that is a decision rather than an
 * oversight: `\|` inside a cell is rare in a model's output, and the code to
 * support it would run on every token of every streamed table.
 */
private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private const val TAB_WIDTH = 4

/**
 * Two spaces per level, not four.
 *
 * Four is the CommonMark rule for code blocks, but a nested list under a
 * numbered item is conventionally indented by two or three spaces, and models
 * emit both. Two means a real nesting is never flattened; the cost is that a
 * stray leading space reads as nesting, which looks like a slight indent rather
 * than like a bug.
 */
private const val INDENT_WIDTH = 2

// -- inline spans ------------------------------------------------------------

/**
 * The colours inline formatting needs, passed in so this stays pure.
 *
 * A `@Composable` that read them from the theme itself could not be unit
 * tested, and inline parsing is exactly the kind of character-by-character code
 * that earns tests.
 */
internal data class InlineColors(
    val text: Color,
    val code: Color,
    val codeBackground: Color,
    val link: Color,
)

/**
 * Inline Markdown: emphasis, code, strikethrough and links.
 *
 * **Order matters.** Code is matched before emphasis, so `` `a * b` `` keeps
 * its asterisk instead of opening an italic that never closes. Triple markers
 * are matched before double, and double before single, for the same reason.
 *
 * **An unmatched marker is literal.** Half a link or an unclosed backtick is
 * what every streamed message looks like for a few hundred milliseconds, so an
 * opener with no closer is emitted as text rather than swallowed. Getting this
 * wrong makes the tail of a streaming answer flicker between markup and prose.
 */
internal fun inlineMarkdown(text: String, colors: InlineColors): AnnotatedString = buildAnnotatedString {
    var index = 0

    fun emphasised(marker: String, style: SpanStyle): Boolean {
        if (!text.startsWith(marker, index)) return false
        val close = text.indexOf(marker, index + marker.length)
        if (close < 0) return false
        val content = text.substring(index + marker.length, close)
        if (content.isEmpty() || content.isBlank()) return false
        val start = length
        append(inlineMarkdown(content, colors))
        addStyle(style, start, length)
        index = close + marker.length
        return true
    }

    while (index < text.length) {
        val char = text[index]

        // Inline code. No padding spaces are added around the content: the old
        // renderer appended them for visual breathing room, and they ended up
        // in the clipboard and broke `read_file`-in-a-sentence mid-word.
        if (char == '`') {
            val close = text.indexOf('`', index + 1)
            if (close > index + 1) {
                val start = length
                append(text.substring(index + 1, close))
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        background = colors.codeBackground,
                        color = colors.code,
                    ),
                    start,
                    length,
                )
                index = close + 1
                continue
            }
        }

        // A link, which is the one inline element that carries behaviour. The
        // url is attached as a real annotation so the platform handles the tap,
        // the long-press menu and accessibility rather than this code.
        if (char == '[') {
            val closeBracket = text.indexOf(']', index)
            if (closeBracket > 0 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                val closeParen = text.indexOf(')', closeBracket)
                if (closeParen > 0) {
                    val label = text.substring(index + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen).trim()
                    if (url.isNotEmpty()) {
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(
                                SpanStyle(
                                    color = colors.link,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) { append(label.ifBlank { url }) }
                        }
                        index = closeParen + 1
                        continue
                    }
                }
            }
        }

        if (emphasised("***", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))) continue
        if (emphasised("**", SpanStyle(fontWeight = FontWeight.Bold))) continue
        if (emphasised("~~", SpanStyle(textDecoration = TextDecoration.LineThrough))) continue
        if (char == '*' && emphasised("*", SpanStyle(fontStyle = FontStyle.Italic))) continue
        // `_` only between word boundaries: snake_case identifiers are
        // everywhere in code and must not turn into italics.
        if (char == '_' &&
            (index == 0 || !text[index - 1].isLetterOrDigit()) &&
            emphasised("_", SpanStyle(fontStyle = FontStyle.Italic))
        ) {
            continue
        }

        append(char)
        index++
    }
}
