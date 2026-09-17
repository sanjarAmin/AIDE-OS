package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A lightweight Markdown renderer for assistant messages.
 *
 * Supports:
 * - Fenced code blocks (` ```lang ... ``` `) with syntax highlighting and action buttons
 * - Headers (`#`, `##`, `###`)
 * - Bullet lists (`- `, `* `) and numbered lists (`1. `)
 * - Blockquotes (`> `)
 * - Inline formatting (`**bold**`, `*italic*`, `` `inline code` ``)
 */
@Composable
fun MarkdownText(
    markdown: String,
    onInsertCode: ((String) -> Unit)? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Code -> {
                    CodeBlock(
                        code = block.code,
                        language = block.language,
                        onInsertCode = onInsertCode,
                    )
                }
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = formatInlineMarkdown(block.text, textColor),
                        style = style.copy(fontWeight = FontWeight.Bold),
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = formatInlineMarkdown(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = formatInlineMarkdown(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Text(
                            text = formatInlineMarkdown(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = textColor.copy(alpha = 0.85f),
                        )
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = formatInlineMarkdown(block.text, textColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                }
            }
        }
    }
}

internal sealed interface MarkdownBlock {
    data class Code(val code: String, val language: String?) : MarkdownBlock
    data class Header(val text: String, val level: Int) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. Fenced Code Block: ```lang
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines += lines[i]
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks += MarkdownBlock.Code(codeLines.joinToString("\n"), lang)
            continue
        }

        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 2. Headers
        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            val headerText = trimmed.drop(level).trim()
            if (headerText.isNotEmpty() && level in 1..4) {
                blocks += MarkdownBlock.Header(headerText, level)
                i++
                continue
            }
        }

        // 3. Unordered list
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
            blocks += MarkdownBlock.BulletItem(trimmed.drop(2).trim())
            i++
            continue
        }

        // 4. Numbered list
        val numberedMatch = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
        if (numberedMatch != null) {
            val number = numberedMatch.groupValues[1]
            val itemText = numberedMatch.groupValues[2]
            blocks += MarkdownBlock.NumberedItem(number, itemText)
            i++
            continue
        }

        // 5. Blockquote
        if (trimmed.startsWith(">")) {
            blocks += MarkdownBlock.Blockquote(trimmed.removePrefix(">").trim())
            i++
            continue
        }

        // 6. Regular paragraph (join consecutive non-empty lines)
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val curr = lines[i]
            val currTrimmed = curr.trim()
            if (currTrimmed.isEmpty() ||
                curr.trimStart().startsWith("```") ||
                currTrimmed.startsWith("#") ||
                currTrimmed.startsWith("- ") ||
                currTrimmed.startsWith("* ") ||
                currTrimmed.startsWith(">") ||
                Regex("^(\\d+)\\.\\s+").containsMatchIn(currTrimmed)
            ) {
                break
            }
            paragraphLines += curr
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraphLines.joinToString("\n"))
        }
    }

    return blocks
}

/**
 * Formats inline Markdown elements (`**bold**`, `*italic*`, and `` `code` ``).
 */
@Composable
fun formatInlineMarkdown(text: String, textColor: Color): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeColor = MaterialTheme.colorScheme.primary

    return remember(text, textColor, codeBg, codeColor) {
        val builder = AnnotatedString.Builder()
        var index = 0

        while (index < text.length) {
            // Check for inline code `...`
            if (text[index] == '`') {
                val nextBacktick = text.indexOf('`', index + 1)
                if (nextBacktick != -1) {
                    val codeContent = text.substring(index + 1, nextBacktick)
                    val start = builder.length
                    builder.append(" $codeContent ")
                    builder.addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = codeBg,
                            color = codeColor,
                            fontWeight = FontWeight.Medium,
                        ),
                        start,
                        builder.length,
                    )
                    index = nextBacktick + 1
                    continue
                }
            }

            // Check for bold **...**
            if (text.startsWith("**", index)) {
                val nextBold = text.indexOf("**", index + 2)
                if (nextBold != -1) {
                    val boldContent = text.substring(index + 2, nextBold)
                    val start = builder.length
                    builder.append(boldContent)
                    builder.addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start,
                        builder.length,
                    )
                    index = nextBold + 2
                    continue
                }
            }

            // Check for italic *...*
            if (text[index] == '*' && (index == 0 || text[index - 1] != '*')) {
                val nextItalic = text.indexOf('*', index + 1)
                if (nextItalic != -1 && (nextItalic + 1 >= text.length || text[nextItalic + 1] != '*')) {
                    val italicContent = text.substring(index + 1, nextItalic)
                    val start = builder.length
                    builder.append(italicContent)
                    builder.addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        start,
                        builder.length,
                    )
                    index = nextItalic + 1
                    continue
                }
            }

            builder.append(text[index])
            index++
        }

        builder.toAnnotatedString()
    }
}
