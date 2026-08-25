package com.osamu.aide.ui.workspace

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
 * Interactive breadcrumb bar displayed above the editor, showing the file path segments
 * relative to the project root with corresponding file/folder icons.
 */
@Composable
fun BreadcrumbBar(
    file: File,
    projectRoot: File?,
    modifier: Modifier = Modifier,
) {
    val relativePath = if (projectRoot != null && file.startsWith(projectRoot)) {
        file.relativeTo(projectRoot).path
    } else {
        file.name
    }

    val segments = relativePath.split(File.separatorChar).filter { it.isNotEmpty() }
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

            segments.forEachIndexed { index, segment ->
                val isLast = index == segments.lastIndex
                Text(
                    text = segment,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLast) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
