package com.osamu.aide.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.ui.layout.AdaptiveWorkspace
import com.osamu.aide.core.ui.layout.PaneBreakpoints
import com.osamu.aide.core.ui.layout.PaneMode
import com.osamu.aide.core.ui.theme.CodeTextStyle
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    projectDir: File,
    onNavigateBack: () -> Unit,
    viewModel: WorkspaceViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(projectDir) { viewModel.open(projectDir) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mode = PaneBreakpoints.forWidth(maxWidth)
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        val body: @Composable () -> Unit = {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                state.projectName.ifEmpty { projectDir.name },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (mode == PaneMode.SINGLE) {
                                        scope.launch { drawerState.open() }
                                    } else {
                                        onNavigateBack()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (mode == PaneMode.SINGLE) {
                                        Icons.Default.Folder
                                    } else {
                                        Icons.AutoMirrored.Filled.ArrowBack
                                    },
                                    contentDescription = if (mode == PaneMode.SINGLE) {
                                        "Show project files"
                                    } else {
                                        "Back to projects"
                                    },
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* Wired to the build engine in M2. */ }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Build and run")
                            }
                        },
                    )
                },
            ) { padding ->
                AdaptiveWorkspace(
                    mode = mode,
                    modifier = Modifier.padding(padding),
                    fileTree = {
                        FileTreePane(
                            nodes = state.visibleNodes,
                            expandedPaths = state.expandedPaths,
                            selected = state.selectedFile,
                            onNodeClick = viewModel::toggle,
                        )
                    },
                    toolPane = { ToolPanePlaceholder() },
                    editor = { EditorPlaceholder(state.selectedFile) },
                )
            }
        }

        if (mode == PaneMode.SINGLE) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        FileTreePane(
                            nodes = state.visibleNodes,
                            expandedPaths = state.expandedPaths,
                            selected = state.selectedFile,
                            onNodeClick = { node ->
                                viewModel.toggle(node)
                                if (!node.isDirectory) scope.launch { drawerState.close() }
                            },
                        )
                    }
                },
                content = body,
            )
        } else {
            body()
        }
    }
}

@Composable
private fun FileTreePane(
    nodes: List<FileNode>,
    expandedPaths: Set<String>,
    selected: File?,
    onNodeClick: (FileNode) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(nodes, key = { it.file.absolutePath }) { node ->
                FileTreeRow(
                    node = node,
                    isExpanded = node.file.absolutePath in expandedPaths,
                    isSelected = selected == node.file,
                    onClick = { onNodeClick(node) },
                )
            }
        }
    }
}

@Composable
private fun FileTreeRow(
    node: FileNode,
    isExpanded: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(color = background, onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Indentation is capped so deep package trees stay readable on a
                // phone-width pane instead of pushing names off-screen.
                .padding(start = (8 + node.depth.coerceAtMost(6) * 12).dp, end = 8.dp)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when {
                    node.isDirectory && isExpanded -> Icons.Default.FolderOpen
                    node.isDirectory -> Icons.Default.Folder
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditorPlaceholder(selected: File?) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = selected?.name ?: "No file open",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = selected?.absolutePath ?: "Select a file from the project tree.",
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ToolPanePlaceholder() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Build output", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Compiler diagnostics and the AI assistant share this pane.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
