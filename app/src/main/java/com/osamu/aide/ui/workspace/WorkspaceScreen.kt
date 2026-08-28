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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.ai.ui.ChatPanel
import com.osamu.aide.core.ui.layout.AdaptiveWorkspace
import com.osamu.aide.core.ui.layout.PaneBreakpoints
import com.osamu.aide.core.ui.layout.PaneMode
import com.osamu.aide.core.ui.theme.CodeTextStyle
import androidx.compose.ui.graphics.Color
import com.osamu.aide.editor.CodeEditorController
import com.osamu.aide.editor.CodeEditorView
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.editor.SearchBar
import com.osamu.aide.editor.SignatureHintOverlay
import com.osamu.aide.editor.SymbolRow
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.toolchain.manager.InstallProgress
import com.osamu.aide.ui.util.FileIcons
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    projectDir: File,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WorkspaceViewModel,
    assistant: AssistantViewModel = koinViewModel(),
    // Its own view model rather than a slice of WorkspaceViewModel: it owns an
    // open GitRepository, which holds file locks and has to be closed.
    git: GitViewModel = koinViewModel(),
    // Also its own: it owns a running shell, which is a child process and has
    // to be killed when the screen goes away.
    terminal: TerminalViewModel = koinViewModel(),
    // A single instance for the whole app: it holds the compiled tree-sitter
    // queries, and rebuilding them per screen is the cost the cache exists to
    // avoid.
    languages: EditorLanguages = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chat by assistant.state.collectAsStateWithLifecycle()
    val isCompleting by assistant.completing.collectAsStateWithLifecycle()
    val gitState by git.state.collectAsStateWithLifecycle()
    val terminalState by terminal.state.collectAsStateWithLifecycle()
    var isChatOpen by remember { mutableStateOf(false) }

    // One tap: open the panel and ask, rather than opening it and leaving the
    // user to retype an error they are looking at. The message is built where
    // the project root is known -- see fixRequest, and why the path it puts in
    // must be relative.
    val askToFix: (Diagnostic) -> Unit = { diagnostic ->
        isChatOpen = true
        assistant.send(fixRequest(diagnostic, projectDir))
    }

    val gitActions = remember(git) {
        GitActions(
            stage = { git.stage(listOf(it)) },
            unstage = { git.unstage(listOf(it)) },
            setMessage = git::setMessage,
            commit = git::commit,
            push = git::push,
            openSettings = onOpenSettings,
            initialise = git::initialise,
            showDiff = git::showDiff,
            dismissDiff = git::dismissDiff,
        )
    }

    val terminalActions = remember(terminal) {
        TerminalActions(
            type = terminal::type,
            typeChar = terminal::typeChar,
            sendKey = { terminal.sendKey(it) },
            interrupt = terminal::interrupt,
            restart = terminal::restart,
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val editorController = remember { CodeEditorController() }

    val completeAtCursor: () -> Unit = {
        val open = state.active
        val cursor = editorController.cursorOffset()
        if (open != null && cursor != null) {
            assistant.complete(
                path = open.file.relativeToProject(projectDir),
                // The buffer as the editor has it, not as it is on disk: the
                // user may not have saved, and completing against the saved
                // copy would continue code they have already changed.
                text = open.document.text,
                cursor = cursor,
                onInsert = editorController::insert,
            )
        }
    }

    // Consumed once the tab it names is showing; see the effect below.
    var pendingJump by remember { mutableStateOf<EditorJump?>(null) }

    LaunchedEffect(projectDir) {
        viewModel.open(projectDir)
        assistant.open(projectDir)
        git.open(projectDir)
        terminal.open(projectDir)
    }
    LaunchedEffect(viewModel) { viewModel.jumps.collect { pendingJump = it } }

    LaunchedEffect(pendingJump, state.activeFile) {
        val jump = pendingJump ?: return@LaunchedEffect
        val root = state.projectRoot ?: return@LaunchedEffect
        val target = File(root, jump.file.path)
        if (state.activeFile != target) return@LaunchedEffect

        // One frame, so the tab switch has actually put that buffer in the
        // widget. Jumping into the outgoing buffer lands on a line of the
        // wrong file, which looks like the diagnostic was wrong.
        withFrameNanos { }
        editorController.jumpTo(jump.line, jump.column)
        pendingJump = null
    }

    // The system installer's confirmation is an Activity. Its result says only
    // that the dialog closed; the outcome arrives on ApkInstaller's broadcast,
    // so there is nothing to do with it here.
    val activityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(assistant) {
        assistant.notices.collect { snackbarHostState.showSnackbar(it) }
    }

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
                            IconButton(onClick = { isChatOpen = true }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = "Ask the assistant",
                                )
                            }
                            if (state.active != null) {
                                IconButton(onClick = viewModel::openSearch) {
                                    Icon(Icons.Default.Search, contentDescription = "Find")
                                }
                            }
                            if (state.isDocumentDirty) {
                                IconButton(onClick = viewModel::save) {
                                    Icon(Icons.Default.Save, contentDescription = "Save")
                                }
                            }
                            // The dock holds git as well as build output, so it
                            // needs a way open that is not "start a build".
                            // Hidden on the wide layout, where the same panels
                            // are always on screen in the side pane.
                            if (!mode.showsToolPane) {
                                IconButton(onClick = viewModel::toggleToolPanel) {
                                    Icon(
                                        Icons.Default.AccountTree,
                                        contentDescription = "Show build and git tools",
                                    )
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
                    modifier = Modifier.padding(padding).imePadding(),
                    fileTree = {
                        FileTreePane(
                            nodes = state.visibleNodes,
                            expandedPaths = state.expandedPaths,
                            selected = state.selectedFile,
                            onNodeClick = viewModel::toggle,
                        )
                    },
                    toolPane = {
                        // Stacked rather than tabbed, unlike the phone dock.
                        // This pane is tall and narrow, and the two things it
                        // shows are the two a user alternates between while
                        // finishing a change: what the build said, and what is
                        // about to be committed. Tabbing them would hide one
                        // behind the other for no gain in space.
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                BuildPane(
                                    state = state.build,
                                    onDiagnosticClick = viewModel::openDiagnostic,
                                    onFixDiagnostic = askToFix,
                                    onLaunchIntent = activityLauncher::launch,
                                )
                            }
                            HorizontalDivider()
                            Box(Modifier.weight(1f).padding(8.dp)) {
                                GitPanel(state = gitState, actions = gitActions)
                            }
                        }
                    },
                    editor = {
                        EditorArea(
                            state = state,
                            languages = languages,
                            controller = editorController,
                            onTextChanged = viewModel::onTextChanged,
                            onSelectTab = viewModel::selectDocument,
                            onCloseTab = viewModel::closeDocument,
                            onCloseSearch = viewModel::closeSearch,
                            onGoToDefinition = viewModel::goToDefinition,
                            onCursorMoved = viewModel::onCursorMoved,
                            onDiagnosticClick = viewModel::openDiagnostic,
                            onFixDiagnostic = askToFix,
                            onComplete = completeAtCursor,
                            isCompleting = isCompleting,
                            onLaunchIntent = activityLauncher::launch,
                            onCloseDock = viewModel::closeBuildPanel,
                            // The wide layout already has a side tool pane; a
                            // dock as well would report the same build twice.
                            showDock = state.isBuildPanelOpen && !mode.showsToolPane,
                            gitState = gitState,
                            gitActions = gitActions,
                            terminalState = terminalState,
                            terminalActions = terminalActions,
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
    }

    if (isChatOpen) {
        // A sheet rather than a pane, in every layout. The assistant is
        // something you reach for and dismiss, and giving it a permanent
        // column would take that width from the editor on exactly the devices
        // that have the least of it. Skipping the half-expanded stop because a
        // half-height chat shows one message and the keyboard.
        ModalBottomSheet(
            onDismissRequest = { isChatOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            ChatPanel(
                state = chat,
                onSend = assistant::send,
                onApproval = assistant::resolveApproval,
                onDismissError = assistant::dismissError,
                onAddKey = {
                    isChatOpen = false
                    onOpenSettings()
                },
                modifier = Modifier.fillMaxSize(),
            )
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

/**
 * Tabs, the search bar, the editor, and the symbol row -- the whole editing
 * surface, stacked.
 *
 * The symbol row is last so it sits directly above the soft keyboard, which is
 * the only place it is any use: reaching past the keyboard to the top of the
 * screen for a semicolon is no better than the two layer-switches it replaces.
 */
@Composable
private fun EditorArea(
    state: WorkspaceUiState,
    languages: EditorLanguages,
    controller: CodeEditorController,
    onTextChanged: (String) -> Unit,
    onSelectTab: (File) -> Unit,
    onCloseTab: (File) -> Unit,
    onCloseSearch: () -> Unit,
    onGoToDefinition: (Int) -> Unit,
    onCursorMoved: (Int) -> Unit,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onFixDiagnostic: (Diagnostic) -> Unit,
    onComplete: () -> Unit,
    isCompleting: Boolean,
    onLaunchIntent: (Intent) -> Unit,
    onCloseDock: () -> Unit,
    showDock: Boolean,
    gitState: GitUiState,
    gitActions: GitActions,
    terminalState: TerminalUiState,
    terminalActions: TerminalActions,
) {
    Column(Modifier.fillMaxSize()) {
        if (state.openFiles.isNotEmpty()) {
            EditorTabs(
                openFiles = state.openFiles,
                activeFile = state.activeFile,
                onSelect = onSelectTab,
                onClose = onCloseTab,
            )
            HorizontalDivider()
        }

        val active = state.active
        if (active != null) {
            BreadcrumbBar(
                file = active.file,
                projectRoot = state.projectRoot,
            )
            HorizontalDivider()
        }

        if (state.isSearchOpen && state.active != null) {
            SearchBar(controller = controller, onDismiss = onCloseSearch)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val active = state.active
            when {
                active != null -> CodeEditorView(
                    document = active.document,
                    openDocuments = state.openFiles.map { it.document },
                    languages = languages,
                    onTextChanged = onTextChanged,
                    onCursorMoved = onCursorMoved,
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                    // Live analysis for the file being edited, the
                    // build's for everything else; see editorDiagnostics.
                    diagnostics = state.editorDiagnostics,
                    projectRoot = state.projectRoot,
                )

                state.documentError != null -> CentredMessage(
                    title = state.openingFile?.name ?: "Could not open",
                    detail = state.documentError,
                )

                state.openingFile != null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                else -> CentredMessage(
                    title = "No file open",
                    detail = "Select a file from the project tree.",
                )
            }

            // Over the editor rather than above it: the hint is transient and
            // should not reflow the code every time the caret enters a call.
            state.analysis.signature?.let { signature ->
                SignatureHintOverlay(
                    signature = signature,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 8.dp, end = 12.dp),
                )
            }
        }

        if (showDock) {
            HorizontalDivider()
            BottomToolDock(
                buildState = state.build,
                // The build's view of the project, plus what the language
                // service has since found in the file being edited.
                problems = state.analysis.diagnostics + state.build.diagnostics,
                gitState = gitState,
                gitActions = gitActions,
                terminalState = terminalState,
                terminalActions = terminalActions,
                onDiagnosticClick = onDiagnosticClick,
                onFixDiagnostic = onFixDiagnostic,
                onLaunchIntent = onLaunchIntent,
                onClose = onCloseDock,
            )
        }

        if (state.active != null) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Fixed, not part of the scrolling row: undo is the one action
                // that must never have scrolled out of reach.
                IconButton(
                    onClick = { controller.cursorOffset()?.let(onGoToDefinition) },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go to definition",
                    )
                }
                // Explicit rather than as-you-type. Every keystroke is a
                // request, and on a phone that is the user's own money and
                // their battery -- so the tap is the budget.
                IconButton(onClick = onComplete, enabled = !isCompleting) {
                    if (isCompleting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "Complete with the assistant",
                        )
                    }
                }
                IconButton(onClick = controller::undo) {
                    Icon(Icons.Default.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = controller::redo) {
                    Icon(Icons.Default.Redo, contentDescription = "Redo")
                }
                SymbolRow(controller = controller, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EditorTabs(
    openFiles: List<OpenFile>,
    activeFile: File?,
    onSelect: (File) -> Unit,
    onClose: (File) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(openFiles, key = { it.file.absolutePath }) { open ->
            val isActive = open.file == activeFile
            val iconInfo = FileIcons.infoFor(open.file, isDirectory = false)
            Surface(
                onClick = { onSelect(open.file) },
                color = if (isActive) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = iconInfo.icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = iconInfo.tint
                    )
                    Text(
                        text = open.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isActive) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                    if (open.isDirty) {
                        // A dot, not an asterisk in the name: the name is what
                        // the eye scans the tab strip for, and it should not
                        // change width as you type.
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                    IconButton(
                        onClick = { onClose(open.file) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close ${open.name}",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
    onFixDiagnostic: (Diagnostic) -> Unit,
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
                    DiagnosticRow(
                        diagnostic = diagnostic,
                        onClick = { onDiagnosticClick(diagnostic) },
                        onFix = { onFixDiagnostic(diagnostic) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    diagnostic: Diagnostic,
    onClick: () -> Unit,
    onFix: () -> Unit,
) {
    val color = when (diagnostic.severity) {
        DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        DiagnosticSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            // Only a located diagnostic has anywhere to go. A signing failure
            // has no file, and a row that looks tappable and does nothing is
            // worse than one that does not.
            onClick = onClick,
            enabled = diagnostic.hasLocation,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = diagnostic.describe(),
                style = CodeTextStyle,
                color = color,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        // Offered on every diagnostic, located or not: a build failure with no
        // file is exactly the kind the user has least idea what to do with.
        IconButton(onClick = onFix, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.AutoFixHigh,
                contentDescription = "Ask the assistant to fix this",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
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
    val iconInfo = FileIcons.infoFor(node.file, node.isDirectory, isExpanded)
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
                imageVector = iconInfo.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = iconInfo.tint,
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
