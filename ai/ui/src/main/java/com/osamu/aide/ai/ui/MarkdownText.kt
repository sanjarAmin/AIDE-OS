package com.osamu.aide.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders one assistant message.
 *
 * **Typography is set for reading a paragraph on a phone, not for a chat
 * bubble.** The body runs at 15sp with 22sp leading -- the default
 * `bodyMedium`'s 20sp is tight for the length of thing a model writes -- and
 * the measure is whatever the panel gives it, which at 360 dp is close to the
 * 60-75 characters that reads comfortably. Nothing here is centred: every
 * block starts on one left edge so the eye returns to the same place.
 *
 * **Spacing encodes structure.** A list is one block with tight rows, and the
 * gap between blocks is larger than the gap inside one, so a five-item list
 * reads as one thought rather than five.
 */
@Composable
fun MarkdownText(
    markdown: String,
    onInsertCode: ((String) -> Unit)? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val colors = MaterialTheme.colorScheme
    val inline = remember(textColor, colors) {
        InlineColors(
            text = textColor,
            code = colors.primary,
            codeBackground = colors.surfaceContainerHighest,
            link = colors.primary,
        )
    }
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Code -> CodeBlock(
                    code = block.code,
                    language = block.language,
                    onInsertCode = onInsertCode,
                    // A fence that has not closed yet is still being written,
                    // so its actions are withheld: copying half a function is
                    // worse than waiting a second for the button to appear.
                    complete = block.complete,
                )

                is MarkdownBlock.Header -> Text(
                    text = inlineMarkdown(block.text, inline),
                    // Two visible sizes, not six. A model's `###` inside a chat
                    // answer is a paragraph label, not a document hierarchy,
                    // and rendering it at titleLarge shouts.
                    style = if (block.level <= 2) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is MarkdownBlock.ListBlock -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (item in block.items) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (item.depth.coerceAtMost(4) * 14).dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            when {
                                item.checked != null -> Icon(
                                    if (item.checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = if (item.checked) "done" else "not done",
                                    tint = if (item.checked) colors.primary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                                )

                                block.ordered -> Text(
                                    text = "${item.marker}.",
                                    style = body,
                                    color = colors.onSurfaceVariant,
                                )

                                else -> Text(
                                    // A dash at depth 0 and a hollow bullet
                                    // deeper, so nesting is legible without
                                    // relying on the indent alone.
                                    text = if (item.depth == 0) "•" else "◦",
                                    style = body,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = inlineMarkdown(item.text, inline),
                                style = body,
                                color = textColor,
                                // The label half of the row, so a long item
                                // wraps instead of squeezing the marker to
                                // nothing. CLAUDE.md's most common defect.
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MarkdownBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .background(colors.outlineVariant),
                    )
                    Text(
                        text = inlineMarkdown(block.text, inline),
                        style = body,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MarkdownBlock.Table -> MarkdownTable(block, inline, textColor)

                MarkdownBlock.Rule -> HorizontalDivider(color = colors.outlineVariant)

                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, inline),
                    style = body,
                    color = textColor,
                )
            }
        }
    }
}

/**
 * A table, scrolled sideways rather than squeezed.
 *
 * **Three columns of prose do not fit in 360 dp**, and the Compose failure mode
 * for trying is the one CLAUDE.md documents: the cells clamp to the space
 * available and the text wraps one character per line. So the table keeps its
 * natural column widths and the row scrolls, which is how every code host shows
 * a wide table on a phone. The header scrolls with it -- a frozen header would
 * need a second scroll state kept in sync, for a table that is typically four
 * rows.
 */
@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    inline: InlineColors,
    textColor: Color,
) {
    val colors = MaterialTheme.colorScheme
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, RoundedCornerShape(8.dp))
            .horizontalScroll(scroll)
            .padding(vertical = 4.dp),
    ) {
        TableRow(
            cells = table.header,
            columns = columns,
            alignments = table.alignments,
            inline = inline,
            color = colors.onSurfaceVariant,
            weight = FontWeight.SemiBold,
        )
        HorizontalDivider(color = colors.outlineVariant)
        for (row in table.rows) {
            TableRow(
                cells = row,
                columns = columns,
                alignments = table.alignments,
                inline = inline,
                color = textColor,
                weight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun TableRow(
    cells: List<String>,
    columns: Int,
    alignments: List<MarkdownBlock.Table.Alignment>,
    inline: InlineColors,
    color: Color,
    weight: FontWeight,
) {
    Row(verticalAlignment = Alignment.Top) {
        for (column in 0 until columns) {
            Text(
                text = inlineMarkdown(cells.getOrElse(column) { "" }, inline),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = weight,
                color = color,
                textAlign = when (alignments.getOrNull(column)) {
                    MarkdownBlock.Table.Alignment.CENTER -> TextAlign.Center
                    MarkdownBlock.Table.Alignment.END -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier
                    // A floor and a ceiling: a one-character column should not
                    // collapse, and a paragraph in a cell should not push the
                    // row metres wide.
                    .widthIn(min = 72.dp, max = 220.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Kept for call sites that format a single line, such as a tool card's detail. */
@Composable
fun inlineMarkdownText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) =
    inlineMarkdown(
        text,
        InlineColors(
            text = color,
            code = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
            link = MaterialTheme.colorScheme.primary,
        ),
    )

