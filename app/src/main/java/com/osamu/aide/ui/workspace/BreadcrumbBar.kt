package com.osamu.aide.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.osamu.aide.ui.util.FileIcons
import java.io.File

/**
 * Where the open file sits, above the editor, a segment per directory.
 *
 * Every segment is a tap into the file tree at that directory -- which the
 * KDoc here claimed for a long time before anything was wired to it, in a bar
 * where nothing happened when you touched it. It is worth wiring rather than
 * un-claiming because the tree is a drawer on a phone: this is the only place
 * on screen that says where the file *is*, and the fastest way to its
 * neighbours.
 *
 * The last segment is the file itself, and reveals the directory holding it.
 */
@Composable
fun BreadcrumbBar(
    file: File,
    projectRoot: File?,
    /** Show [File] in the project tree: a directory, or the parent of a file. */
    onSegmentClick: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val relativePath = if (projectRoot != null && file.startsWith(projectRoot)) {
        file.relativeTo(projectRoot).path
    } else {
        file.name
    }

    val names = relativePath.split(File.separatorChar).filter { it.isNotEmpty() }
    // Each segment paired with the directory a tap on it should reveal, walked
    // down from the root rather than up from the file: the path is relative, so
    // the root is the only absolute thing here.
    val segments = buildList {
        var current: File? = projectRoot ?: file.parentFile
        names.forEachIndexed { index, name ->
            current = current?.let { File(it, name) }
            val isLast = index == names.lastIndex
            add(name to if (isLast) current?.parentFile else current)
        }
    }
    val iconInfo = FileIcons.infoFor(file, isDirectory = false)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = iconInfo.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = iconInfo.tint,
            )

            segments.forEachIndexed { index, (name, target) ->
                val isLast = index == segments.lastIndex
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLast) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // Padding inside the clickable, so the target is bigger
                    // than the glyphs. A breadcrumb cannot reach 48 dp without
                    // becoming a toolbar, but 4 dp of type is not a target.
                    modifier = Modifier
                        .clickable(enabled = target != null) { target?.let(onSegmentClick) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )

                if (!isLast) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
