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
 * **It shows the directories, not the file.** The tab strip directly above it
 * already carries the file name, and a phone screen cannot afford to say the
 * same word twice in two rows -- for a file at the project root the bar had
 * nothing else to say at all, so it drew a second copy of the tab and took
 * 34 dp to do it. Now it draws the path that leads to the file and nothing
 * else, and it disappears entirely when there is no path.
 *
 * The cost is that opening a root-level file after a nested one shifts the
 * editor up by the height of this bar. That is a real cost and smaller than a
 * permanent duplicate row.
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

    // The trailing element is the file, and the tab above owns that.
    val names = relativePath.split(File.separatorChar).filter { it.isNotEmpty() }.dropLast(1)
    if (names.isEmpty()) return

    // Each segment paired with the directory a tap on it should reveal, walked
    // down from the root rather than up from the file: the path is relative, so
    // the root is the only absolute thing here.
    val segments = buildList {
        var current: File? = projectRoot ?: file.parentFile
        names.forEach { name ->
            current = current?.let { File(it, name) }
            add(name to current)
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
                    // The directory holding the file is the one worth reading
                    // at a glance; the ones above it are context.
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
