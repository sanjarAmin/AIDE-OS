package com.osamu.aide.ui.workspace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.ui.util.FileIcons
import java.io.File

/**
 * The project's files, as a tree.
 *
 * **It has to read as a tree.** A flat list of indented names is what this was,
 * and on a phone-width drawer the indentation alone does not say which folder a
 * file belongs to -- the eye has nothing to follow back up. The guides are the
 * one structural device here, and they are information rather than decoration:
 * one hairline per level of ancestry, running the full height of the row so
 * consecutive rows join into a rail.
 *
 * The rest is deliberately quiet, because the file-type icons already carry
 * colour and a second coloured thing per row would compete with them. Selection
 * is the one place colour is spent: a rounded fill in `primaryContainer`, inset
 * from the edges so the shape belongs to the row rather than to the pane.
 *
 * What a row says beyond its name, all of it real state the workspace already
 * holds: a chevron that turns when the folder is open, a name in medium weight
 * when the file is open in a tab, and a dot when that tab has unsaved work.
 * Nothing else, because a row is 34 dp and everything in it competes.
 */
@Composable
internal fun FileTreePane(
    projectName: String,
    language: SourceLanguage?,
    nodes: List<FileNode>,
    expandedPaths: Set<String>,
    selected: File?,
    /** Paths with a tab open, which are drawn a shade stronger than the rest. */
    openPaths: Set<String>,
    /** Of those, the ones with unsaved edits. */
    dirtyPaths: Set<String>,
    onNodeClick: (FileNode) -> Unit,
    onCollapseAll: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            FileTreeHeader(
                projectName = projectName,
                language = language,
                // Offered only when there is something to collapse; a button
                // that cannot change anything is worse than no button.
                onCollapseAll = onCollapseAll.takeIf { expandedPaths.isNotEmpty() },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                // **The project's own folder is not a row.** The header above
                // already names it, and a tree whose first line repeats the
                // title it sits under wastes the one line a drawer can least
                // afford. It stays in the model, where expanding and
                // collapsing still hang off it.
                items(nodes.drop(1), key = { it.file.absolutePath }) { node ->
                    val path = node.file.absolutePath
                    FileTreeRow(
                        node = node,
                        isExpanded = path in expandedPaths,
                        isSelected = selected == node.file,
                        isOpen = path in openPaths,
                        isDirty = path in dirtyPaths,
                        onClick = { onNodeClick(node) },
                    )
                }
            }
        }
    }
}

/**
 * Names the project the tree belongs to.
 *
 * The drawer covers the app bar that carries this on a phone, so without it
 * the pane opens on a list of folders with nothing saying whose they are. The
 * badge repeats the app bar's, deliberately: it is the same fact in the same
 * shape, and the drawer is where someone looks when they have lost their place.
 */
@Composable
private fun FileTreeHeader(
    projectName: String,
    language: SourceLanguage?,
    onCollapseAll: (() -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Weighted, for the reason the app bar's own title is: the badge
            // and the button are fixed, the name is what gives way.
            Text(
                text = projectName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            language?.let { lang ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = lang.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (onCollapseAll != null) {
                IconButton(onClick = onCollapseAll, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.UnfoldLess,
                        contentDescription = "Collapse all folders",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FileTreeRow(
    node: FileNode,
    isExpanded: Boolean,
    isSelected: Boolean,
    isOpen: Boolean,
    isDirty: Boolean,
    onClick: () -> Unit,
) {
    val iconInfo = FileIcons.infoFor(node.file, node.isDirectory, isExpanded)
    // **Not `outlineVariant`.** That token is a border against a card edge and
    // is nearly the surface itself in the dark scheme -- #1E2838 on #111722 --
    // so the rail disappeared in the theme this app is designed for. Muted text
    // at low alpha lands at the same weight against either ground.
    val guide = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    // Capped, so a deep package tree stays readable in a 260 dp pane instead of
    // pushing names off the side.
    // Depth 1 is the outermost row drawn, since the root is not one.
    val level = (node.depth - 1).coerceIn(0, MAX_GUIDE_DEPTH)
    val turn by animateFloatAsState(if (isExpanded) 90f else 0f, label = "chevron")

    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // One line per ancestor, full height, so the rows join up.
                repeat(level) { depth ->
                    val x = (GUTTER_DP + depth * INDENT_DP + CHEVRON_CENTRE_DP).dp.toPx()
                    drawLine(
                        color = guide,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            .padding(start = 4.dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            // **Indented rather than full-bleed**, so the guides to its left
            // stay visible: a selected row used to cover the rail with a solid
            // block, and the tree came apart at exactly the row being looked at.
            modifier = Modifier.fillMaxWidth().padding(start = (level * INDENT_DP).dp),
        ) {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .padding(start = 4.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (node.isDirectory) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(turn),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Files line up with their siblings' folder icons rather
                    // than with the chevrons.
                    Spacer(Modifier.width(16.dp))
                }
                Icon(
                    imageVector = iconInfo.icon,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = iconInfo.tint,
                )
                Text(
                    text = foldedName(node.name, MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = when {
                        node.isDirectory -> FontWeight.Medium
                        isOpen -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // **The name gives way; the dot does not.** A `Row` squeezes
                    // rather than overflows, and an unweighted name would leave
                    // the marker nothing to be laid out in. CLAUDE.md.
                    modifier = Modifier.weight(1f),
                )
                if (isDirty) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                            .semantics { contentDescription = "Unsaved changes" },
                    )
                }
            }
        }
    }
}

/**
 * A folded run of directories, with its separators set back.
 *
 * `java/com/example/large` is one row -- see [FileNode.name] -- and reading it
 * is easier when the last segment, which is the one that names the package the
 * files are in, is not competing with three slashes for attention.
 */
private fun foldedName(name: String, separator: Color): AnnotatedString {
    if ('/' !in name) return AnnotatedString(name)
    return buildAnnotatedString {
        name.split('/').forEachIndexed { index, segment ->
            if (index > 0) withStyle(SpanStyle(color = separator)) { append("/") }
            append(segment)
        }
    }
}

/** Left margin before the first guide, matching the row's own start padding. */
private const val GUTTER_DP = 4

/** One level of nesting. Tighter than a desktop tree, because the pane is 260 dp. */
private const val INDENT_DP = 14

/** Where a chevron's centre falls inside a row, so a guide points at it. */
private const val CHEVRON_CENTRE_DP = 12

private const val MAX_GUIDE_DEPTH = 6
