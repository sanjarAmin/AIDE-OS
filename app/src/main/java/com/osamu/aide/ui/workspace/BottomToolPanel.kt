package com.osamu.aide.ui.workspace

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.osamu.aide.core.ui.theme.CodeTextStyle
import com.osamu.aide.engine.api.Diagnostic

enum class ToolTab(val title: String, val icon: ImageVector) {
    BUILD("Build", Icons.Default.PlayCircleOutline),
    PROBLEMS("Problems", Icons.Default.BugReport),
    LOGCAT("Logcat", Icons.AutoMirrored.Filled.ListAlt),
    TERMINAL("Terminal", Icons.Default.Terminal),
}

/**
 * Modern multi-tab bottom tool dock for the workspace.
 * Replaces the single transient modal sheet with a persistent/expandable tool container.
 */
@Composable
fun BottomToolDock(
    buildState: BuildUiState,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(ToolTab.BUILD) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Tab Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolTab.entries.forEach { tab ->
                        val isSelected = tab == selectedTab
                        val badgeCount = when (tab) {
                            ToolTab.PROBLEMS -> buildState.diagnostics.size
                            ToolTab.BUILD -> if (buildState.isRunning) 1 else 0
                            else -> 0
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            modifier = Modifier.clickable { selectedTab = tab },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (tab == ToolTab.BUILD && buildState.isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = badgeCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close dock",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            HorizontalDivider()

            // Tool Content Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(8.dp),
            ) {
                when (selectedTab) {
                    ToolTab.BUILD -> {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(buildState.log) { line ->
                                Text(
                                    text = line,
                                    style = CodeTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 1.dp),
                                )
                            }
                        }
                    }
                    ToolTab.PROBLEMS -> {
                        if (buildState.diagnostics.isEmpty()) {
                            Text(
                                text = "No problems found in project.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth()) {
                                items(buildState.diagnostics) { diagnostic ->
                                    Text(
                                        text = diagnostic.describe(),
                                        style = CodeTextStyle,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = diagnostic.hasLocation) {
                                                onDiagnosticClick(diagnostic)
                                            }
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    ToolTab.LOGCAT -> {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(
                                text = "Logcat stream connected (Application ID: live)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Waiting for process output...",
                                style = CodeTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    ToolTab.TERMINAL -> {
                        Column(Modifier.fillMaxWidth().padding(8.dp)) {
                            Text(
                                text = "$ aide-os --version\nAIDE-OS Native Shell v1.0 [aarch64]\n$ ",
                                style = CodeTextStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
