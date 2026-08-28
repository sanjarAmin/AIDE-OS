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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.AccountTree
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
import androidx.compose.material3.TextButton
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

/** Tabs whose content needs more than a strip: see the height below. */
private val TALL_TABS = setOf(ToolTab.GIT, ToolTab.TERMINAL)

enum class ToolTab(val title: String, val icon: ImageVector) {
    BUILD("Build", Icons.Default.PlayCircleOutline),
    PROBLEMS("Problems", Icons.Default.BugReport),
    GIT("Git", Icons.Default.AccountTree),
    LOGCAT("Logcat", Icons.AutoMirrored.Filled.ListAlt),
    TERMINAL("Terminal", Icons.Default.Terminal),
}

/**
 * The workspace's bottom tool dock.
 *
 * Replaces the transient bottom sheet on layouts with no room for a side tool
 * pane. Persistent rather than modal, because build output is something you
 * read *while* editing -- a sheet that covers the code you are fixing has to be
 * dismissed before you can act on what it said.
 *
 * [problems] is separate from `buildState.diagnostics` on purpose: the language
 * service reports the file being edited as it is typed, and the build reports
 * the project as it was on disk. The Problems tab wants the union, most-recent
 * first, which is what the caller assembles.
 */
@Composable
fun BottomToolDock(
    buildState: BuildUiState,
    problems: List<Diagnostic>,
    gitState: GitUiState,
    gitActions: GitActions,
    terminalState: TerminalUiState,
    terminalActions: TerminalActions,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onFixDiagnostic: (Diagnostic) -> Unit,
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
                            ToolTab.PROBLEMS -> problems.size
                            ToolTab.BUILD -> if (buildState.isRunning) 1 else 0
                            // The number of files a commit would take: the one
                            // figure worth carrying on a tab nobody is looking at.
                            ToolTab.GIT -> gitState.status.staged.size
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
            //
            // **Taller for the tabs that need it.** 200dp was sized when this
            // dock held build output and nothing else. Git spends most of that
            // on chrome it cannot drop -- a branch line, an identity warning, a
            // commit field -- and what was left for the changed files was about
            // one row, overlapping the row beneath it, verified in the running
            // app. The terminal has the same problem for the same reason: a
            // command field and a status line leave almost nothing for output.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (selectedTab in TALL_TABS) 340.dp else 200.dp)
                    .padding(8.dp),
            ) {
                when (selectedTab) {
                    ToolTab.BUILD -> {
                        Column(Modifier.fillMaxWidth()) {
                            // The one build failure the user can act on: the
                            // install permission is a Settings toggle, not a
                            // prompt. Losing this row when the dock replaced
                            // the sheet would strand an otherwise good build.
                            buildState.install?.let { install ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = install.message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    install.settings?.let { settings ->
                                        TextButton(onClick = { onLaunchIntent(settings) }) {
                                            Text("Settings")
                                        }
                                    }
                                }
                            }
                            if (buildState.log.isEmpty() && buildState.install == null) {
                                Text(
                                    text = "Build output appears here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                    }
                    ToolTab.PROBLEMS -> {
                        if (problems.isEmpty()) {
                            Text(
                                text = "No problems found in project.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp),
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth()) {
                                items(problems) { diagnostic ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = diagnostic.describe(),
                                            style = CodeTextStyle,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = diagnostic.hasLocation) {
                                                    onDiagnosticClick(diagnostic)
                                                }
                                                .padding(vertical = 4.dp),
                                        )
                                        IconButton(
                                            onClick = { onFixDiagnostic(diagnostic) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.AutoFixHigh,
                                                contentDescription =
                                                    "Ask the assistant to fix this",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Both tabs are placeholders and say so. A mocked-up log
                    // stream or shell prompt reads as a working feature, and
                    // the first thing it teaches the user is that the UI lies
                    // about what the app can do.
                    ToolTab.GIT -> GitPanel(state = gitState, actions = gitActions)
                    ToolTab.LOGCAT -> NotBuiltYet(
                        "Reading the running app's log needs the installed build to be " +
                            "attached to. Nothing here is wired up yet.",
                    )
                    ToolTab.TERMINAL -> TerminalPanel(
                        state = terminalState,
                        actions = terminalActions,
                    )
                }
            }
        }
    }
}

/** An empty tool tab that is honest about being empty. */
@Composable
private fun NotBuiltYet(explanation: String) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text(
            text = "Not built yet",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
