package com.osamu.aide.ui.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.debug.DebugHandshakeProvider
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.SourceLanguage
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ChatUiState
import com.osamu.aide.ai.ui.ChatPanel
import com.osamu.aide.core.ui.layout.AdaptiveWorkspace
import com.osamu.aide.core.ui.layout.PaneBreakpoints
import com.osamu.aide.core.ui.layout.PaneMode
import com.osamu.aide.core.ui.theme.CodeTextStyle
import androidx.compose.ui.graphics.Color
import com.osamu.aide.editor.CodeEditorController
import com.osamu.aide.editor.CodeEditorView
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.editor.EditorPreferences
import com.osamu.aide.editor.EditorSettings
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
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    projectDir: File,
    onNavigateBack: () -> Unit,
    onOpenSettings: (String?) -> Unit,
    viewModel: WorkspaceViewModel,
    assistant: AssistantViewModel = koinViewModel(),
    // Its own view model rather than a slice of WorkspaceViewModel: it owns an
    // open GitRepository, which holds file locks and has to be closed.
    git: GitViewModel = koinViewModel(),
    // Also its own: it owns a running shell, which is a child process and has
    // to be killed when the screen goes away.
    terminal: TerminalViewModel = koinViewModel(),
    // The same: it owns a running `logcat`, which is a child process.
    logcat: LogcatViewModel = koinViewModel(),
    // And a socket into another app, whose threads it may hold suspended.
    debug: DebugViewModel = koinViewModel(),
    // A single instance for the whole app: it holds the compiled tree-sitter
    // queries, and rebuilding them per screen is the cost the cache exists to
    // avoid.
    languages: EditorLanguages = koinInject(),
    // Collected here rather than read inside the editor, so a change made in
    // Settings while a file is open reaches the widget on the next frame.
    editorPreferences: EditorPreferences = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chat by assistant.state.collectAsStateWithLifecycle()
    val isCompleting by assistant.completing.collectAsStateWithLifecycle()
    val gitState by git.state.collectAsStateWithLifecycle()
    val terminalState by terminal.state.collectAsStateWithLifecycle()
    val logcatState by logcat.state.collectAsStateWithLifecycle()
    val debugState by debug.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Bumped to bring the Debug tab forward; see BottomToolDock.debugFocus.
    var debugFocus by remember { mutableStateOf(0) }
    val editorSettings by editorPreferences.settings.collectAsStateWithLifecycle()

    // **Re-read git when the screen comes back.** The panel's own empty state
    // sends the user to Settings to set a name and email, and until this
    // existed they came back to the same message and a disabled Commit --
    // hasIdentity was read when the panel loaded and never again. The identity
    // is not the only thing that can change while the app is away: a file
    // edited from a desktop over USB, or a commit made in the terminal, are
    // both invisible to a panel that only reloads after its own operations.
    LifecycleResumeEffect(git) {
        git.refresh()
        onPauseOrDispose { }
    }
    var isChatOpen by remember { mutableStateOf(false) }
    var sideToolFocus by remember { mutableStateOf<SideTool?>(null) }

    val fixScope = rememberCoroutineScope()

    val gitActions = remember(git) {
        GitActions(
            stage = { git.stage(listOf(it)) },
            unstage = { git.unstage(listOf(it)) },
            setMessage = git::setMessage,
            commit = git::commit,
            push = git::push,
            openSettings = { onOpenSettings("git") },
            initialise = git::initialise,
            showDiff = git::showDiff,
            dismissDiff = git::dismissDiff,
            stageAll = git::stageAll,
            unstageAll = git::unstageAll,
            discard = { git.discard(listOf(it)) },
            checkoutBranch = { name, createNew -> git.checkoutBranch(name, createNew) },
        )
    }

    val logcatActions = remember(logcat) {
        LogcatActions(
            start = logcat::start,
            stop = logcat::stop,
            clear = logcat::clear,
            setFilter = logcat::setFilter,
            setLevel = logcat::setLevel,
        )
    }

    val terminalActions = remember(terminal) {
        TerminalActions(
            type = terminal::type,
            typeChar = terminal::typeChar,
            sendKey = { terminal.sendKey(it) },
            interrupt = terminal::interrupt,
            restart = terminal::restart,
            resize = terminal::resize,
        )
    }

    // Why Debug is not offered, in the user's terms, or null when it is.
    val debugUnavailable = when {
        state.projectLanguage == null -> null
        state.projectLanguage !in BUILDS_AN_APK ->
            "Debugging works on apps: Java and Kotlin projects. This one runs a program instead."
        else -> null
    }
    val startDebugging: () -> Unit = {
        viewModel.debug(DebugHandshakeProvider.authority(context.packageName))
        debugFocus++
    }
    val debugActions = DebugActions(
        start = startDebugging.takeIf { debugUnavailable == null && state.projectLanguage != null },
        resume = debug::resume,
        stepOver = debug::stepOver,
        stepInto = debug::stepInto,
        stepOut = debug::stepOut,
        stop = debug::stop,
        selectFrame = debug::selectFrame,
        toggleExpanded = debug::toggleExpanded,
        openBreakpoint = { viewModel.reveal(it.file, it.line) },
        removeBreakpoint = debug::removeBreakpoint,
    )

    val snackbarHostState = remember { SnackbarHostState() }
    val editorController = remember { CodeEditorController() }

    val completeAtCursor: () -> Unit = {
        val open = state.active
        val cursor = editorController.cursorOffset()
        // **Both from the editor.** The comment here used to say "the buffer as
        // the editor has it, not as it is on disk" and then passed
        // `open.document.text`, which is exactly the disk copy: an edit lands in
        // the view model's pending map and `document.text` keeps what was last
        // saved. Slicing that at a live cursor offset described a file the user
        // was not looking at -- the assistant received the line *before* the one
        // being typed, saw nothing to add, and said so.
        val buffer = editorController.text()
        if (open != null && cursor != null && buffer != null) {
            assistant.complete(
                path = open.file.relativeToProject(projectDir),
                text = buffer,
                cursor = cursor.coerceIn(0, buffer.length),
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
        debug.open(projectDir)
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

    // **Where the debugger stopped is where the editor goes.** Keyed on the
    // point, so selecting another frame moves the editor too, and a Resume
    // that clears it leaves the cursor where it is rather than jumping away.
    LaunchedEffect(debugState.executionPoint) {
        val point = debugState.executionPoint ?: return@LaunchedEffect
        viewModel.reveal(point.file, point.line)
        viewModel.showToolPanel()
        debugFocus++
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

                is WorkspaceEvent.DebugReady -> {
                    debug.start(event.applicationId, event.port)
                    debugFocus++
                }

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

        val openAssistant: () -> Unit = {
            if (mode == PaneMode.TRIPLE) {
                sideToolFocus = SideTool.ASSISTANT
            } else {
                isChatOpen = true
            }
        }

        val askToFix: (Diagnostic) -> Unit = { diagnostic ->
            openAssistant()
            fixScope.launch {
                viewModel.saveAllNow()
                assistant.send(fixRequest(diagnostic, projectDir))
            }
        }

        val body: @Composable () -> Unit = {
            Scaffold(
                topBar = {
                    val infiniteTransition = rememberInfiniteTransition(label = "workspacePulse")
                    val aiPulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 0.85f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "aiPulseAlpha",
                    )
                    val buildPulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.92f,
                        targetValue = 1.14f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "buildPulseScale",
                    )
                    val buildPulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 0.65f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "buildPulseAlpha",
                    )

                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // **The name gives way, the badge does not.**
                                // Unweighted, the name measured at full width
                                // and the language badge was laid out in what
                                // was left -- which, once a file was open and
                                // Debug joined the actions, was one letter
                                // wide: J / A / V / A down the toolbar. The
                                // row squeeze CLAUDE.md records, eighth time.
                                Text(
                                    state.projectName.ifEmpty { projectDir.name },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                state.projectLanguage?.let { lang ->
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            text = lang.displayName.uppercase(),
                                            maxLines = 1,
                                            softWrap = false,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
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
                            // **What does not fit goes in a menu, and the name
                            // keeps a width.** Seven 48 dp buttons are 336 dp,
                            // and with the drawer button that is wider than a
                            // 360 or 411 dp phone before the title gets
                            // anything: with a file unsaved the project name
                            // was laid out 25 px wide and drew as "…". The
                            // row squeeze CLAUDE.md records, again in the
                            // toolbar. ToolbarLayout does the arithmetic. The consequential buttons -- Save,
                            // Debug, Build -- always stay; the others fold
                            // into More when the window is too narrow.
                            val secondary = buildList {
                                add(ToolbarAction.ASSISTANT)
                                if (state.active != null) add(ToolbarAction.FIND)
                                // Only where it means something: a fast-engine
                                // project's dependencies are in its descriptor,
                                // and there is nothing to ask Gradle.
                                if (state.projectEngine == BuildEngine.GRADLE) add(ToolbarAction.SYNC)
                                if (!mode.showsToolPane) add(ToolbarAction.TOOLS)
                            }
                            val primaryCount = listOf(
                                state.isDocumentDirty,
                                !state.build.isRunning && debugActions.start != null && !debugState.isActive,
                                true,
                            ).count { it }
                            val inline = ToolbarLayout.fitsInline(
                                widthDp = LocalConfiguration.current.screenWidthDp,
                                primary = primaryCount,
                                secondary = secondary.size,
                            )
                            if (inline) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isCompleting) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = aiPulseAlpha * 0.35f),
                                                    CircleShape,
                                                ),
                                        )
                                    }
                                    IconButton(onClick = openAssistant) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Chat,
                                            contentDescription = "Ask the assistant",
                                            tint = if (isCompleting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                if (ToolbarAction.FIND in secondary) {
                                    IconButton(onClick = viewModel::openSearch) {
                                        Icon(Icons.Default.Search, contentDescription = "Find")
                                    }
                                }
                                if (ToolbarAction.SYNC in secondary) {
                                    IconButton(
                                        onClick = viewModel::sync,
                                        enabled = !state.build.isRunning,
                                    ) {
                                        Icon(Icons.Default.Sync, contentDescription = "Sync with Gradle")
                                    }
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
                            if (inline && ToolbarAction.TOOLS in secondary) {
                                IconButton(onClick = viewModel::toggleToolPanel) {
                                    Icon(
                                        Icons.Default.AccountTree,
                                        contentDescription = "Show build and git tools",
                                    )
                                }
                            }
                            if (!inline) {
                                var menuOpen by remember { mutableStateOf(false) }
                                Box(contentAlignment = Alignment.Center) {
                                    // The assistant's pulse moves with it, or an
                                    // inline completion in progress would show
                                    // nowhere.
                                    if (isCompleting) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = aiPulseAlpha * 0.35f),
                                                    CircleShape,
                                                ),
                                        )
                                    }
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Ask the assistant") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                            onClick = { menuOpen = false; openAssistant() },
                                        )
                                        if (ToolbarAction.FIND in secondary) {
                                            DropdownMenuItem(
                                                text = { Text("Find") },
                                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                                onClick = { menuOpen = false; viewModel.openSearch() },
                                            )
                                        }
                                        if (ToolbarAction.SYNC in secondary) {
                                            DropdownMenuItem(
                                                text = { Text("Sync with Gradle") },
                                                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                                enabled = !state.build.isRunning,
                                                onClick = { menuOpen = false; viewModel.sync() },
                                            )
                                        }
                                        if (ToolbarAction.TOOLS in secondary) {
                                            DropdownMenuItem(
                                                text = { Text("Build and git tools") },
                                                leadingIcon = { Icon(Icons.Default.AccountTree, contentDescription = null) },
                                                onClick = { menuOpen = false; viewModel.toggleToolPanel() },
                                            )
                                        }
                                    }
                                }
                            }
                            // The same button does two different things, and
                            // says which: a JavaScript project has no APK to
                            // build and no install to wait for, it just runs.
                            val runsRatherThanBuilds =
                                state.projectLanguage == SourceLanguage.JAVASCRIPT
                            // Beside Run rather than behind a menu: it is the
                            // other way to start the app. Absent where there
                            // is nothing to debug, and while a build or a
                            // session is already going, so it never sits
                            // disabled.
                            if (!state.build.isRunning && debugActions.start != null && !debugState.isActive) {
                                IconButton(onClick = startDebugging) {
                                    Icon(
                                        Icons.Default.BugReport,
                                        contentDescription = "Debug",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Box(contentAlignment = Alignment.Center) {
                                if (state.build.isRunning) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .scale(buildPulseScale)
                                            .background(
                                                MaterialTheme.colorScheme.error.copy(alpha = buildPulseAlpha * 0.35f),
                                                CircleShape,
                                            ),
                                    )
                                    IconButton(onClick = viewModel::stopBuild) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = when {
                                                state.build.isRun -> "Stop the program"
                                                state.build.isSync -> "Stop reading the project"
                                                else -> "Stop the build"
                                            },
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                } else {
                                    IconButton(onClick = viewModel::build) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = if (runsRatherThanBuilds) {
                                                "Run"
                                            } else {
                                                "Build and run"
                                            },
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
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
                            projectName = state.projectName.ifEmpty { projectDir.name },
                            language = state.projectLanguage,
                            nodes = state.visibleNodes,
                            expandedPaths = state.expandedPaths,
                            selected = state.selectedFile,
                            openPaths = state.openPaths,
                            dirtyPaths = state.dirtyPaths,
                            onNodeClick = viewModel::toggle,
                            onCollapseAll = viewModel::collapseAll,
                            onLocateActiveFile = state.selectedFile?.let { file ->
                                { viewModel.revealInTree(if (file.isDirectory) file else file.parentFile ?: file) }
                            },
                        )
                    },
                    toolPane = {
                        // The build stays visible; everything else shares the
                        // half below it.
                        //
                        // It was Build over Git, stacked, on the reasoning that
                        // those are the two a user alternates between while
                        // finishing a change. True, and it left the **terminal
                        // unreachable on a tablet** -- a shipped feature with no
                        // way in on a whole class of device, which is worse than
                        // a tab. The build keeps the top half because it is the
                        // thing you glance at rather than work in.
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) {
                                BuildPane(
                                    state = state.build,
                                    onDiagnosticClick = viewModel::openDiagnostic,
                                    onFixDiagnostic = askToFix,
                                    onLaunchIntent = activityLauncher::launch,
                                    onInstallDependencies = viewModel::installDependencies
                                        .takeIf { state.projectLanguage == SourceLanguage.JAVASCRIPT },
                                )
                            }
                            HorizontalDivider()
                            Box(Modifier.weight(1f)) {
                                SideToolTabs(
                                    problems = state.analysis.diagnostics + state.build.diagnostics,
                                    gitState = gitState,
                                    gitActions = gitActions,
                                    terminalState = terminalState,
                                    terminalActions = terminalActions,
                                    logcatState = logcatState,
                                    logcatActions = logcatActions,
                                    debugState = debugState,
                                    debugActions = debugActions,
                                    debugUnavailable = debugUnavailable,
                                    debugBuildStatus = debugBuildStatus(state.build),
                                    debugFocus = debugFocus,
                                    chatState = chat,
                                    onSendChat = assistant::send,
                                    onApprovalChat = assistant::resolveApproval,
                                    onDismissErrorChat = assistant::dismissError,
                                    onAddKeyChat = { onOpenSettings("ai") },
                                    onSignInGoogleChat = { onOpenSettings("ai") },
                                    onSwitchProviderChat = assistant::switchProvider,
                                    onSwitchModelChat = assistant::switchModel,
                                    onToggleShareContextChat = assistant::toggleShareContext,
                                    onCancelSendChat = assistant::cancelSend,
                                    onNewChat = assistant::newChat,
                                    onInsertCode = editorController::insert,
                                    activeFileName = state.selectedFile?.name,
                                    sideToolFocus = sideToolFocus,
                                    projectRoot = state.projectRoot,
                                    applicationId = state.projectApplicationId,
                                    onDiagnosticClick = viewModel::openDiagnostic,
                                    onFixDiagnostic = askToFix,
                                )
                            }
                        }
                    },
                    editor = {
                        EditorArea(
                            state = state,
                            languages = languages,
                            controller = editorController,
                            onTextChanged = { text ->
                                // Breakpoints first, so the gutter redrawn for
                                // this edit already has them on their new lines.
                                state.active?.let { debug.onEdited(it.file, text) }
                                viewModel.onTextChanged(text)
                            },
                            onSelectTab = viewModel::selectDocument,
                            onCloseTab = viewModel::closeDocument,
                            onRevealInTree = { directory ->
                                viewModel.revealInTree(directory)
                                // The tree is a drawer on a phone, so revealing
                                // into one that is shut is the same as doing
                                // nothing. The wide layout already shows it.
                                if (mode == PaneMode.SINGLE) {
                                    scope.launch { drawerState.open() }
                                }
                            },
                            onCloseSearch = viewModel::closeSearch,
                            onGoToDefinition = viewModel::goToDefinition,
                            onCursorMoved = viewModel::onCursorMoved,
                            onDiagnosticClick = viewModel::openDiagnostic,
                            onFixDiagnostic = askToFix,
                            onComplete = completeAtCursor,
                            isCompleting = isCompleting,
                            onLaunchIntent = activityLauncher::launch,
                            onCloseDock = viewModel::closeBuildPanel,
                            onInstallDependencies = viewModel::installDependencies,
                            onBuildRelease = { viewModel.build(debuggable = false) },
                            onSelectEngine = viewModel::setEngine,
                            editorSettings = editorSettings,
                            // The wide layout already has a side tool pane; a
                            // dock as well would report the same build twice.
                            showDock = state.isBuildPanelOpen && !mode.showsToolPane,
                            gitState = gitState,
                            gitActions = gitActions,
                            terminalState = terminalState,
                            terminalActions = terminalActions,
                            logcatState = logcatState,
                            logcatActions = logcatActions,
                            debugState = debugState,
                            debugActions = debugActions,
                            debugUnavailable = debugUnavailable,
                            debugFocus = debugFocus,
                            onLineNumberTap = debug::toggleBreakpoint,
                            // Phones only: the wide layout shows the tree as a
                            // permanent pane, so there is nothing to open.
                            onOpenTree = if (mode == PaneMode.SINGLE) {
                                { scope.launch { drawerState.open() } }
                            } else {
                                null
                            },
                        )
                    },
                )
            }
        }

        if (mode == PaneMode.SINGLE) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // **No edge swipe, and that is the point of this line.** A
                // modal drawer opens on a drag from the left edge, and the
                // thing directly under that edge is a code editor that scrolls
                // horizontally -- so reaching the start of a long line by
                // dragging opened the file tree instead. The gesture and the
                // content want the same pixels and the drawer wins, which
                // makes the editor feel broken rather than the drawer feel
                // helpful.
                //
                // Nothing is lost: the tree has an explicit button, and a
                // gesture with no discoverable affordance was never how anyone
                // found it. Closing it by swiping still works -- that gesture
                // starts on the sheet, not on the editor.
                gesturesEnabled = false,
                drawerContent = {
                    ModalDrawerSheet {
                        FileTreePane(
                            projectName = state.projectName.ifEmpty { projectDir.name },
                            language = state.projectLanguage,
                            nodes = state.visibleNodes,
                            expandedPaths = state.expandedPaths,
                            selected = state.selectedFile,
                            openPaths = state.openPaths,
                            dirtyPaths = state.dirtyPaths,
                            onNodeClick = { node ->
                                viewModel.toggle(node)
                                if (!node.isDirectory) scope.launch { drawerState.close() }
                            },
                            onCollapseAll = viewModel::collapseAll,
                            onLocateActiveFile = state.selectedFile?.let { file ->
                                { viewModel.revealInTree(if (file.isDirectory) file else file.parentFile ?: file) }
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
                    onOpenSettings("ai")
                },
                onSignInGoogle = {
                    isChatOpen = false
                    onOpenSettings("ai")
                },
                onSwitchProvider = assistant::switchProvider,
                onSwitchModel = assistant::switchModel,
                onToggleShareContext = assistant::toggleShareContext,
                onCancelSend = assistant::cancelSend,
                onNewChat = assistant::newChat,
                onInsertCode = editorController::insert,
                activeFileName = state.selectedFile?.name,
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
    onRevealInTree: (File) -> Unit,
    onCloseSearch: () -> Unit,
    onGoToDefinition: (Int) -> Unit,
    onCursorMoved: (Int) -> Unit,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onFixDiagnostic: (Diagnostic) -> Unit,
    onComplete: () -> Unit,
    isCompleting: Boolean,
    onLaunchIntent: (Intent) -> Unit,
    onCloseDock: () -> Unit,
    onInstallDependencies: () -> Unit,
    onBuildRelease: () -> Unit,
    onSelectEngine: (BuildEngine) -> Unit,
    editorSettings: EditorSettings,
    showDock: Boolean,
    gitState: GitUiState,
    gitActions: GitActions,
    terminalState: TerminalUiState,
    terminalActions: TerminalActions,
    logcatState: LogcatUiState,
    logcatActions: LogcatActions,
    debugState: DebugUiState,
    debugActions: DebugActions,
    debugUnavailable: String?,
    debugFocus: Int,
    onLineNumberTap: (File, Int) -> Unit,
    /**
     * Opens the file tree, or null where it is already on screen.
     *
     * Null on a tablet: the tree is a permanent pane there, so a control to
     * reveal it would reveal something that never went away. Phones only.
     */
    onOpenTree: (() -> Unit)?,
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
                onSegmentClick = onRevealInTree,
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
                    settings = editorSettings,
                    breakpointLines = debugState.breakpointLinesIn(active.file),
                    executionLine = debugState.executionPoint
                        ?.takeIf { it.file == active.file }
                        ?.line,
                    // Only where a breakpoint can mean something. A tap on the
                    // gutter of a layout XML file setting a mark that can
                    // never fire would teach the user the feature is broken.
                    onLineNumberTap = if (active.file.extension in DEBUGGABLE_SOURCES &&
                        debugUnavailable == null
                    ) {
                        { line -> onLineNumberTap(active.file, line) }
                    } else {
                        null
                    },
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
            // **Only with no file open, which is when the bottom is empty.**
            // The row below carries the compact control while editing; a
            // floating one then would sit on top of Go to definition, which is
            // fixed there precisely so it never moves. With nothing open there
            // is no row, nothing to cover, and opening the tree is the only
            // thing the screen is asking for -- the empty state says so in
            // words directly above this.
            if (state.active == null && onOpenTree != null) {
                ProjectTreeTab(
                    onClick = onOpenTree,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 24.dp),
                )
            }

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
                logcatState = logcatState,
                logcatActions = logcatActions,
                debugState = debugState,
                debugActions = debugActions,
                debugUnavailable = debugUnavailable,
                projectRoot = state.projectRoot,
                debugFocus = debugFocus,
                applicationId = state.projectApplicationId,
                onDiagnosticClick = onDiagnosticClick,
                onFixDiagnostic = onFixDiagnostic,
                onLaunchIntent = onLaunchIntent,
                onClose = onCloseDock,
                // Null for every other language: npm is not a thing a Java
                // project can be offered, and a disabled button that is always
                // disabled is worse than no button.
                onInstallDependencies = onInstallDependencies
                    .takeIf { state.projectLanguage == SourceLanguage.JAVASCRIPT },
                // Only where the product is an APK: JavaScript and C# start a
                // program, and there is nothing to sign.
                onBuildRelease = onBuildRelease
                    .takeIf { state.projectLanguage in BUILDS_AN_APK },
                // Null for the languages with no engine: JavaScript and C#
                // run rather than build, and offering them a choice between
                // two APK pipelines would be offering a choice that does
                // nothing.
                engine = state.projectEngine.takeIf { state.projectLanguage in BUILDS_AN_APK },
                onSelectEngine = onSelectEngine,
            )
        }

        if (state.active != null) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                // **The file tree, within reach of a thumb.** It is also in the
                // top bar, which on a tall phone is a stretch -- and it used to
                // be reachable by swiping from the left edge, which had to go
                // because that gesture and a horizontally scrolling editor want
                // the same pixels. This row is where the hands already are.
                //
                // Same icon as the toolbar button, deliberately: one symbol for
                // one thing beats a second, cleverer glyph meaning the same.
                onOpenTree?.let { open ->
                    IconButton(onClick = open) {
                        Icon(Icons.Default.Folder, contentDescription = "Show project files")
                    }
                }
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

/**
 * A pull-tab for the file tree, at the bottom-left edge.
 *
 * Flush to the left edge rather than inset, because that is where the drawer
 * used to come from: the tab stands in for the edge swipe that was removed, and
 * looking like a sliver of the sheet itself is the clearest way to say so.
 *
 * **The glyph is a chevron and not a triangle.** A solid right-pointing
 * triangle is `PlayArrow` to anyone who has used this app, and in an IDE that
 * means Run -- a button next to an editor that looks like Run and opens a file
 * tree is a worse surprise than a less striking icon.
 *
 * The animation is a slow nudge outward rather than a pulse or a glow. It says
 * "this slides out from here", which is the one thing a new user needs to know
 * about it; a pulse would only say "look at me".
 */
@Composable
internal fun ProjectTreeTab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "projectTreeTab")
    val nudge by transition.animateFloat(
        initialValue = 0f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "projectTreeTabNudge",
    )

    Surface(
        onClick = onClick,
        // Square against the screen edge, rounded where it leaves it.
        shape = RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = 22.dp,
            bottomEnd = 22.dp,
        ),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        // `offset` and not `padding`: this animates every frame, and offset is
        // applied at layout without re-measuring the tab or anything near it.
        modifier = modifier.offset { IntOffset(nudge.roundToInt(), 0) },
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Labelled, because this is the empty state and discoverability is
            // the entire job. Two fixed-width children, so no `weight` is owed
            // here -- there is no variable-length label to squeeze the icon.
            Text("Project files", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Show project files",
                modifier = Modifier.size(18.dp),
            )
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
    onInstallDependencies: (() -> Unit)?,
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
                    text = state.stage?.displayName
                        ?: state.outcome
                        ?: when {
                            state.isRun -> "Running"
                            state.isSync -> "Reading the project"
                            else -> "Build output"
                        },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.outcome != null && !state.succeeded && !state.isRunning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }

            if (onInstallDependencies != null) {
                TextButton(
                    onClick = onInstallDependencies,
                    enabled = !state.isRunning,
                ) { Text("Install dependencies") }
            }

            if (state.log.isEmpty() && state.diagnostics.isEmpty()) {
                Text(
                    text = when {
                        state.isRun -> "Output from the program appears here."
                        state.isSync -> "The modules Gradle reads appear here."
                        else -> "Compiler diagnostics appear here."
                    },
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
                // The rationale says the size, every time. It used to be
                // appended here instead, but only for a component under the
                // SDK licence -- so a rationale that mentioned the size and
                // happened to be about the platform said it twice: "about 62
                // MB to download. Editing works without it. It is about 62
                // MB." One place to write it, and every caller writes it.
                Text(
                    state.rationale,
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

                state.progress?.let {
                    InstallProgressRow(it, megabytes, state.component.displayName)
                }
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
private fun InstallProgressRow(
    progress: InstallProgress,
    megabytes: Long,
    // The component being installed. This row said "Extracting android.jar"
    // whatever was extracting, which was true only of the first component this
    // dialog ever had -- and read, while Node unpacked, as the app doing
    // something nobody asked for. The title above says the right thing; there
    // is no reason for the line under it to disagree.
    componentName: String,
) {
    Column(Modifier.padding(top = 16.dp)) {
        val label = when (progress) {
            is InstallProgress.Downloading ->
                "Downloading — ${progress.bytes / (1024 * 1024)} of $megabytes MB"
            InstallProgress.Verifying -> "Verifying the download"
            InstallProgress.Extracting -> "Extracting $componentName"
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

/**
 * The languages whose ▶ produces an APK, and so have a build engine at all.
 *
 * JavaScript and C# run rather than build; a choice between the fast pipeline
 * and Gradle would be a choice between two things neither of them uses.
 */
/**
 * Files a breakpoint can be placed in: the ones ECJ and kotlinc turn into
 * classes with line tables.
 */
private val DEBUGGABLE_SOURCES = setOf("java", "kt")

private val BUILDS_AN_APK = setOf(
    SourceLanguage.JAVA,
    SourceLanguage.KOTLIN,
    SourceLanguage.C,
    SourceLanguage.CPP,
)

/**
 * The tools that share the lower half of a tablet's side pane.
 *
 * A tab row rather than another stack: three panes in a column would leave each
 * one too short to use, and the terminal in particular needs the height. Build
 * is deliberately absent — it owns the half above this and is never hidden.
 */
@Composable
private fun SideToolTabs(
    problems: List<Diagnostic>,
    gitState: GitUiState,
    gitActions: GitActions,
    terminalState: TerminalUiState,
    terminalActions: TerminalActions,
    logcatState: LogcatUiState,
    logcatActions: LogcatActions,
    debugState: DebugUiState,
    debugActions: DebugActions,
    debugUnavailable: String?,
    debugBuildStatus: String?,
    debugFocus: Int,
    chatState: ChatUiState,
    onSendChat: (String) -> Unit,
    onApprovalChat: (Boolean) -> Unit,
    onDismissErrorChat: () -> Unit,
    onAddKeyChat: () -> Unit,
    onSignInGoogleChat: () -> Unit,
    onSwitchProviderChat: (AiProviderType) -> Unit,
    onSwitchModelChat: (String) -> Unit,
    onToggleShareContextChat: (Boolean) -> Unit,
    onCancelSendChat: () -> Unit,
    onNewChat: () -> Unit,
    onInsertCode: (String) -> Unit,
    activeFileName: String?,
    sideToolFocus: SideTool?,
    projectRoot: File?,
    applicationId: String?,
    onDiagnosticClick: (Diagnostic) -> Unit,
    onFixDiagnostic: (Diagnostic) -> Unit,
) {
    var selected by remember { mutableStateOf(SideTool.GIT) }
    LaunchedEffect(debugFocus) {
        if (debugFocus > 0) selected = SideTool.DEBUG
    }
    LaunchedEffect(sideToolFocus) {
        sideToolFocus?.let { selected = it }
    }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = SideTool.entries.indexOf(selected)) {
            SideTool.entries.forEach { tool ->
                Tab(
                    selected = selected == tool,
                    onClick = { selected = tool },
                    text = { Text(tool.title, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        Box(Modifier.weight(1f).padding(8.dp)) {
            when (selected) {
                SideTool.DEBUG -> DebugPanel(
                    state = debugState,
                    actions = debugActions,
                    projectRoot = projectRoot,
                    unavailableReason = debugUnavailable,
                    buildStatus = debugBuildStatus,
                )
                SideTool.GIT -> GitPanel(state = gitState, actions = gitActions)
                SideTool.PROBLEMS -> ProblemsList(problems, onDiagnosticClick, onFixDiagnostic)
                SideTool.TERMINAL -> TerminalPanel(
                    state = terminalState,
                    actions = terminalActions,
                )
                SideTool.LOGCAT -> LogcatPanel(
                    state = logcatState,
                    actions = logcatActions,
                    applicationId = applicationId,
                )
                SideTool.ASSISTANT -> ChatPanel(
                    state = chatState,
                    onSend = onSendChat,
                    onApproval = onApprovalChat,
                    onDismissError = onDismissErrorChat,
                    onAddKey = onAddKeyChat,
                    onSignInGoogle = onSignInGoogleChat,
                    onSwitchProvider = onSwitchProviderChat,
                    onSwitchModel = onSwitchModelChat,
                    onToggleShareContext = onToggleShareContextChat,
                    onCancelSend = onCancelSendChat,
                    onNewChat = onNewChat,
                    onInsertCode = onInsertCode,
                    activeFileName = activeFileName,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Logcat joined these when it stopped being a placeholder.
 *
 * It was left out while it only ever said "not built yet" -- a tab that costs
 * width on the one layout where width is already scarce. Leaving it out now
 * that it works would be the tablet's terminal all over again: a shipped
 * feature with no way in on a whole class of device.
 */
private enum class SideTool(val title: String) {
    // First: while a session runs it is the tab that changes on its own, and
    // the build output it depends on is in the half above.
    DEBUG("Debug"),
    GIT("Git"),
    PROBLEMS("Problems"),
    TERMINAL("Terminal"),
    LOGCAT("Logcat"),
    ASSISTANT("AI"),
}

/** The toolbar buttons that may fold into More; see [ToolbarLayout]. */
internal enum class ToolbarAction { ASSISTANT, FIND, SYNC, TOOLS }

/**
 * Whether every toolbar button fits beside the project name.
 *
 * Counted rather than measured: a `TopAppBar` gives its title whatever the
 * actions leave, and the actions are laid out first, so by the time a
 * measurement could tell the name is squeezed the decision has been made.
 * [MIN_TITLE_DP] is enough for a short project name and its language badge.
 */
internal object ToolbarLayout {
    private const val BUTTON_DP = 48
    private const val MIN_TITLE_DP = 96
    /** The navigation button and the bar's own start and end insets. */
    private const val CHROME_DP = BUTTON_DP + 8

    fun fitsInline(widthDp: Int, primary: Int, secondary: Int): Boolean =
        CHROME_DP + (primary + secondary) * BUTTON_DP + MIN_TITLE_DP <= widthDp
}
