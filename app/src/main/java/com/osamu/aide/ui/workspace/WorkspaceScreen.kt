package com.osamu.aide.ui.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.osamu.aide.editor.CodeEditorView
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.toolchain.manager.InstallProgress
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    projectDir: File,
    onNavigateBack: () -> Unit,
    viewModel: WorkspaceViewModel,
    // A single instance for the whole app: it holds the compiled tree-sitter
    // queries, and rebuilding them per screen is the cost the cache exists to
    // avoid.
    languages: EditorLanguages = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(projectDir) { viewModel.open(projectDir) }

    // The system installer's confirmation is an Activity. Its result says only
    // that the dialog closed; the outcome arrives on ApkInstaller's broadcast,
    // so there is nothing to do with it here.
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkspaceEvent.LaunchNow -> activityLauncher.launch(event.intent)

                is WorkspaceEvent.Notice -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        event.action?.let(activityLauncher::launch)
                    }
                }
            }
        }
    }

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
                            if (state.isDocumentDirty) {
                                IconButton(onClick = viewModel::save) {
                                    Icon(Icons.Default.Save, contentDescription = "Save")
                                }
                            }
                            if (state.build.isRunning) {
                                IconButton(onClick = viewModel::stopBuild) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop the build")
                                }
                            } else {
                                IconButton(onClick = viewModel::build) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Build and run",
                                    )
                                }
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    toolPane = {
                        BuildPane(
                            state = state.build,
                            onDiagnosticClick = viewModel::openDiagnostic,
                            onLaunchIntent = activityLauncher::launch,
                        )
                    },
                    editor = {
                        EditorPane(
                            state = state,
                            languages = languages,
                            onTextChanged = viewModel::onTextChanged,
                        )
                    },
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

        // Only the three-pane layout has somewhere to put build output. Below
        // that width it arrives as a sheet, which is also where it belongs on a
        // phone: it is transient, and it should not cost the editor half the
        // screen for the rest of the session.
        if (state.isBuildPanelOpen && !mode.showsToolPane) {
            ModalBottomSheet(
                onDismissRequest = viewModel::closeBuildPanel,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                BuildPane(
                    state = state.build,
                    onDiagnosticClick = { diagnostic ->
                        viewModel.openDiagnostic(diagnostic)
                        viewModel.closeBuildPanel()
                    },
                    onLaunchIntent = activityLauncher::launch,
                    modifier = Modifier.heightIn(min = 200.dp, max = 420.dp),
                )
            }
        }
    }

    state.platform?.let { platform ->
        PlatformInstallDialog(
            state = platform,
            onAccept = viewModel::acceptSdkLicense,
            onInstall = viewModel::installPlatform,
            onDismiss = viewModel::dismissPlatformInstall,
        )
    }
}

@Composable
private fun EditorPane(
    state: WorkspaceUiState,
    languages: EditorLanguages,
    onTextChanged: (String) -> Unit,
) {
    val document = state.document
    when {
        document != null -> CodeEditorView(
            document = document,
            languages = languages,
            onTextChanged = onTextChanged,
            modifier = Modifier.fillMaxSize(),
        )

        state.documentError != null -> CentredMessage(
            title = state.selectedFile?.name ?: "Could not open",
            detail = state.documentError,
        )

        state.selectedFile != null -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        else -> CentredMessage(
            title = "No file open",
            detail = "Select a file from the project tree.",
        )
    }
}

@Composable
private fun CentredMessage(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BuildPane(
    state: BuildUiState,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = state.stage?.displayName ?: state.outcome ?: "Build output",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.outcome != null && !state.succeeded && !state.isRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (state.log.isEmpty() && state.diagnostics.isEmpty()) {
                Text(
                    text = "Compiler diagnostics appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            state.install?.let { install ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = install.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // The one failure the user can do something about: the
                    // install permission is a Settings toggle, not a prompt.
                    install.settings?.let { settings ->
                        TextButton(onClick = { onLaunchIntent(settings) }) { Text("Settings") }
                    }
                }
            }

            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(state.log) { line ->
                    Text(
                        text = line,
                        style = CodeTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                items(state.diagnostics) { diagnostic ->
                    DiagnosticRow(diagnostic, onClick = { onDiagnosticClick(diagnostic) })
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(diagnostic: Diagnostic, onClick: () -> Unit) {
    val color = when (diagnostic.severity) {
        DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        // Only a located diagnostic has anywhere to go. A signing failure has
        // no file, and a row that looks tappable and does nothing is worse than
        // one that does not.
        onClick = onClick,
        enabled = diagnostic.hasLocation,
    ) {
        Text(
            text = diagnostic.describe(),
            style = CodeTextStyle,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun PlatformInstallDialog(
    state: PlatformUiState,
    onAccept: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val megabytes = state.component.archiveBytes / (1024 * 1024)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.component.displayName) },
        text = {
            Column {
                Text(
                    "Building needs android.jar, which cannot be shipped inside " +
                        "AIDE-OS. It is downloaded once, from Google, and is about " +
                        "$megabytes MB.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (!state.licenseAccepted) {
                    Text(
                        "Android SDK Terms and Conditions",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // The whole agreement, scrollable. Summarising it would be
                    // presenting our words as Google's.
                    Text(
                        text = state.licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                state.progress?.let { InstallProgressRow(it, megabytes) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.isInstalling,
                onClick = {
                    if (!state.licenseAccepted) onAccept()
                    onInstall()
                },
            ) {
                Text(if (state.licenseAccepted) "Download" else "Accept and download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

@Composable
private fun InstallProgressRow(progress: InstallProgress, megabytes: Long) {
    Column(Modifier.padding(top = 16.dp)) {
        val label = when (progress) {
            is InstallProgress.Downloading ->
                "Downloading — ${progress.bytes / (1024 * 1024)} of $megabytes MB"
            InstallProgress.Verifying -> "Verifying the download"
            InstallProgress.Extracting -> "Extracting android.jar"
            is InstallProgress.Installed -> "Installed"
            is InstallProgress.Failed -> progress.message
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (progress is InstallProgress.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        val fraction = (progress as? InstallProgress.Downloading)?.fraction
        if (progress !is InstallProgress.Failed && progress !is InstallProgress.Installed) {
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
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
