package com.osamu.aide.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.ui.util.FileIcons
import java.io.File

/**
 * The project's files, as a modern, polished tree.
 *
 * **It has to read as a tree.** Visual guide lines connect parent to child, while
 * active items are highlighted with an electric accent bar and high-contrast styling.
 * The header provides fast in-tree filtering, active-file location, and folder collapse.
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
    onLocateActiveFile: (() -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var isFilterVisible by remember { mutableStateOf(false) }

    val allRows = nodes.drop(1)
    val filteredNodes = remember(allRows, searchQuery) {
        if (searchQuery.isBlank()) {
            allRows
        } else {
            allRows.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.file.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            FileTreeHeader(
                projectName = projectName,
                language = language,
                itemCount = allRows.size,
                isFilterActive = isFilterVisible || searchQuery.isNotEmpty(),
                onToggleFilter = {
                    isFilterVisible = !isFilterVisible
                    if (!isFilterVisible) searchQuery = ""
                },
                onLocateActiveFile = onLocateActiveFile,
                onCollapseAll = onCollapseAll.takeIf { expandedPaths.isNotEmpty() },
            )

            AnimatedVisibility(
                visible = isFilterVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Filter files...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            },
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (filteredNodes.isEmpty() && searchQuery.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No files matching",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "\"$searchQuery\"",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(filteredNodes, key = { it.file.absolutePath }) { node ->
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

            FileTreeFooter(
                totalFiles = allRows.size,
                openCount = openPaths.size,
            )
        }
    }
}

/**
 * Names the project and provides explorer actions.
 */
@Composable
private fun FileTreeHeader(
    projectName: String,
    language: SourceLanguage?,
    itemCount: Int,
    isFilterActive: Boolean,
    onToggleFilter: () -> Unit,
    onLocateActiveFile: (() -> Unit)?,
    onCollapseAll: (() -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "EXPLORER",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (itemCount > 0) {
                    Text(
                        text = "$itemCount items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FolderSpecial,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                language?.let { lang ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = lang.displayName.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onToggleFilter,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        if (isFilterActive) Icons.Default.FilterListOff else Icons.Default.Search,
                        contentDescription = "Filter files",
                        modifier = Modifier.size(18.dp),
                        tint = if (isFilterActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (onLocateActiveFile != null) {
                    IconButton(
                        onClick = onLocateActiveFile,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.CenterFocusStrong,
                            contentDescription = "Locate active file",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onCollapseAll != null) {
                    IconButton(
                        onClick = onCollapseAll,
                        modifier = Modifier.size(36.dp),
                    ) {
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
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
    val guide = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val level = (node.depth - 1).coerceIn(0, MAX_GUIDE_DEPTH)
    val turn by animateFloatAsState(if (isExpanded) 90f else 0f, label = "chevron")

    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
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
            color = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                isOpen -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else -> Color.Transparent
            },
            border = if (isSelected) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (level * INDENT_DP).dp),
        ) {
            Row(
                modifier = Modifier
                    .height(34.dp)
                    .padding(start = 4.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isSelected) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                }
                if (node.isDirectory) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).rotate(turn),
                        tint = if (isExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Icon(
                    imageVector = iconInfo.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconInfo.tint,
                )
                Text(
                    text = foldedName(node.name, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = when {
                        node.isDirectory -> FontWeight.SemiBold
                        isOpen -> FontWeight.Medium
                        else -> FontWeight.Normal
                    },
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                        isOpen -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
 * A folded run of directories, with its intermediate path segments muted
 * and the target directory highlighted.
 */
private fun foldedName(name: String, separator: Color): AnnotatedString {
    if ('/' !in name) return AnnotatedString(name)
    val segments = name.split('/')
    return buildAnnotatedString {
        segments.forEachIndexed { index, segment ->
            if (index > 0) withStyle(SpanStyle(color = separator)) { append("/") }
            if (index == segments.lastIndex) {
                append(segment)
            } else {
                withStyle(SpanStyle(color = separator)) {
                    append(segment)
                }
            }
        }
    }
}

/**
 * Footer providing subtle project statistics at the bottom of the sidebar.
 */
@Composable
private fun FileTreeFooter(
    totalFiles: Int,
    openCount: Int,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Text(
                        text = "$totalFiles items",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (openCount > 0) {
                    Text(
                        text = "$openCount open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

private const val GUTTER_DP = 4
private const val INDENT_DP = 14
private const val CHEVRON_CENTRE_DP = 12
private const val MAX_GUIDE_DEPTH = 6
