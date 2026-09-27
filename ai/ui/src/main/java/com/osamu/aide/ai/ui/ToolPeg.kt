package com.osamu.aide.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.core.ui.theme.CodeTextStyle

/**
 * One tool call, hanging off the turn's rail.
 *
 * **This is the audit trail, and that is why it is not hidden.** Every other
 * chat app treats a tool call as plumbing to collapse away; here the assistant
 * is reading and rewriting the user's source, so what it touched is the part
 * they will want to check -- and the part they will want to find again after a
 * bad edit.
 *
 * **A row, not a card.** A card would spend 32 dp of a 360 dp screen on padding
 * and a shadow, and would make each call look like a separate event. A row with
 * a glyph on the rail reads as a step within the turn, which is what it is, and
 * matches the vernacular a developer already knows: a build log, a diff gutter,
 * a git graph.
 *
 * Collapsed it says what ran, on what, and how long it took. Opened it shows
 * the arguments and the output verbatim in monospace, because that text is
 * literally what was on disk and paraphrasing it would be a lie.
 */
@Composable
internal fun ToolPeg(entry: ChatEntry.Tool) {
    val colors = MaterialTheme.colorScheme
    // Survives rotation and the panel being reopened. A tool card expanded to
    // read a long result and collapsed by a configuration change is a small
    // betrayal, and an easy one to avoid.
    var expanded by rememberSaveable(entry.id.toString()) { mutableStateOf(false) }

    val (icon, tint) = when {
        entry.declined -> Icons.Default.Block to colors.outline
        entry.failed -> Icons.Default.ErrorOutline to colors.error
        else -> entry.name.toolIcon() to colors.primary
    }

    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = Color.Transparent,
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().testTag(TOOL_PEG_TAG),
        ) {
            Row(
                modifier = Modifier.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))

                // The verb and its object, in the two voices this panel uses:
                // the tool's name is the app's own word for it, the target is
                // monospace because it is a path on the device.
                Text(
                    text = entry.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (entry.failed) colors.error else colors.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = entry.detail,
                    style = CodeTextStyle.copy(fontSize = 11.5.sp),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    // **The label half of the row.** A long path must shorten
                    // rather than squeeze the duration and the chevron off the
                    // screen; CLAUDE.md counts nine instances of getting this
                    // wrong, and the control is always the half that matters.
                    modifier = Modifier.weight(1f),
                )

                // Only when it was slow enough to be worth explaining. A "0.0s"
                // on every row is noise, and on a phone with a local model the
                // slow ones are what the user is trying to account for.
                if (entry.durationMs >= SLOW_MS) {
                    Text(
                        text = entry.durationMs.asSeconds(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        AnimatedVisibility(expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.input.isNotEmpty()) {
                    for ((key, value) in entry.input) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                style = CodeTextStyle.copy(fontSize = 11.5.sp),
                                color = colors.onSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                val result = entry.result
                if (!result.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceContainerLow, RoundedCornerShape(6.dp))
                            // Bounded, and scrollable in both directions: a
                            // `list_files` on a real project returns hundreds of
                            // lines, and an unbounded result would push the
                            // answer off the screen entirely.
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        Text(
                            text = result,
                            style = CodeTextStyle.copy(fontSize = 11.5.sp),
                            color = if (entry.failed) colors.error else colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun Long.asSeconds(): String =
    if (this < 1_000) "${this}ms" else String.format("%.1fs", this / 1000.0)

/**
 * A glyph per tool, so a turn's shape is readable without reading it.
 *
 * Grouped by what the tool does to the project rather than by which module
 * provides it: reading, searching, changing, running. Anything unrecognised
 * gets the "changes something" glyph, which is the safer default to show for a
 * tool nobody has classified yet.
 */
private fun String.toolIcon(): ImageVector = when (this) {
    "list_files" -> Icons.Default.FolderOpen
    "read_file" -> Icons.Default.Description
    "grep", "search_definitions" -> Icons.Default.Search
    "git_status", "git_diff", "git_log" -> Icons.Default.AccountTree
    "run_shell" -> Icons.Default.Terminal
    "run_build", "read_build_errors" -> Icons.Default.Build
    "check_kotlin", "explain_kotlin_symbol" -> Icons.Default.Code
    else -> Icons.Default.EditNote
}

/** Under this, the number says nothing a person did not already feel. */
private const val SLOW_MS = 400L

const val TOOL_PEG_TAG = "chat-tool"
