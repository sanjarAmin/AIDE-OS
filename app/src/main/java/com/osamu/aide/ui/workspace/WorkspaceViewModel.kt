package com.osamu.aide.ui.workspace

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectFiles
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.editor.DocumentStore
import com.osamu.aide.editor.EditorLanguages
import com.osamu.aide.editor.SourceDocument
import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.fast.ApkInstaller
import com.osamu.aide.engine.fast.InstallStatus
import com.osamu.aide.toolchain.manager.InstallProgress
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What happened when the built APK was handed to the system installer.
 *
 * Part of the build's state rather than a snackbar: a successful build opens
 * the output panel, and on a phone that panel is a sheet sitting exactly where
 * a snackbar would appear. [settings] is non-null when the only way forward is
 * for the user to grant the install permission.
 */
data class InstallUiState(val message: String, val settings: Intent? = null)

/** How the last (or current) build is going. */
data class BuildUiState(
    val isRunning: Boolean = false,
    /** What it is doing right now, or null when it is not running. */
    val stage: BuildStage? = null,
    /** One line per finished stage, in order. */
    val log: List<String> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
    /** How it ended, in one line. Null until it has. */
    val outcome: String? = null,
    val succeeded: Boolean = false,
    val install: InstallUiState? = null,
)

/**
 * The platform-download prompt.
 *
 * Non-null exactly while it is on screen. The licence text is carried rather
 * than read by the dialog because it comes off disk and a composable must not.
 */
data class PlatformUiState(
    val component: ToolchainComponent,
    val licenseText: String,
    val licenseAccepted: Boolean,
    /**
     * Why this download is being asked for, in the user's terms.
     *
     * Carried rather than derived in the dialog because the components differ
     * in kind: one is Google's platform under Google's licence, another is half
     * a gigabyte of compiler. "A component is required" would be true of both
     * and useful for neither.
     */
    val rationale: String,
    val progress: InstallProgress? = null,
) {
    val isInstalling: Boolean
        get() = progress is InstallProgress.Downloading ||
            progress is InstallProgress.Verifying ||
            progress is InstallProgress.Extracting
}

/**
 * Something the screen must do that state cannot express: start an Activity, or
 * say something once.
 *
 * A channel rather than state, because both are edges. Putting "installed" in
 * the state would replay the snackbar on every rotation, and putting an Intent
 * there would launch the installer twice.
 */
sealed interface WorkspaceEvent {

    /** Launch this now -- the system installer's confirmation dialog. */
    data class LaunchNow(val intent: Intent) : WorkspaceEvent

    /** A snackbar. [action] is offered as its button when there is one. */
    data class Notice(
        val message: String,
        val action: Intent? = null,
        val actionLabel: String? = null,
    ) : WorkspaceEvent
}

/** One open tab. */
/**
 * What the language service last said about the file being edited.
 *
 * Only ever one file's worth. Analysis follows the cursor: running it for every
 * open tab would keep a compiler busy on files nobody is looking at, and the
 * gutter only ever renders the file on screen anyway.
 */
/** Somewhere to put the cursor: a project-relative file, 1-based position. */
data class EditorJump(val file: File, val line: Int, val column: Int)

data class AnalysisUiState(
    val file: File? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val isRunning: Boolean = false,
    /** The call the caret is inside, if it is inside one. */
    val signature: String? = null,
)

data class OpenFile(
    val document: SourceDocument,
    val isDirty: Boolean = false,
) {
    val file: File get() = document.file
    val name: String get() = document.name
}

data class WorkspaceUiState(
    val projectName: String = "",
    val projectRoot: File? = null,
    /** Flattened visible tree: only expanded directories contribute children. */
    val visibleNodes: List<FileNode> = emptyList(),
    val expandedPaths: Set<String> = emptySet(),
    /** Open tabs, in the order they were opened. */
    val openFiles: List<OpenFile> = emptyList(),
    /** Which tab is showing. Null when nothing is open. */
    val activeFile: File? = null,
    /** Set while a file is being read, so the pane can say so. */
    val openingFile: File? = null,
    val documentError: String? = null,
    val isSearchOpen: Boolean = false,
    val build: BuildUiState = BuildUiState(),
    val analysis: AnalysisUiState = AnalysisUiState(),
    val isBuildPanelOpen: Boolean = false,
    val platform: PlatformUiState? = null,
) {
    val active: OpenFile? get() = openFiles.firstOrNull { it.file == activeFile }

    /** What the file tree highlights: the open tab, or the file being read. */
    val selectedFile: File? get() = activeFile ?: openingFile

    val isDocumentDirty: Boolean get() = active?.isDirty == true

    val hasUnsavedChanges: Boolean get() = openFiles.any { it.isDirty }

    /**
     * What the gutter shows.
     *
     * Live analysis wins over the build's diagnostics for the file it is about,
     * because it is newer -- the build describes what was on disk when it ran,
     * and the user has been typing since. Everything else falls back to the
     * build, which is the only thing that knows about resources, dexing and
     * signing.
     */
    val editorDiagnostics: List<Diagnostic>
        get() = if (analysis.file != null && analysis.file == activeFile) {
            analysis.diagnostics
        } else {
            build.diagnostics
        }
}

class WorkspaceViewModel(
    private val dispatchers: DispatcherProvider,
    private val projects: ProjectRepository,
    private val documents: DocumentStore,
    private val builder: ProjectBuilder,
    private val toolchain: ToolchainManager,
    private val installer: ApkInstaller,
    private val languageServices: LanguageServices,
    private val languages: EditorLanguages,
    private val dependencies: ProjectDependencies,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val _events = Channel<WorkspaceEvent>(Channel.BUFFERED)
    val events: Flow<WorkspaceEvent> = _events.receiveAsFlow()

    private var rootNode: FileNode? = null
    private var project: Project? = null

    /**
     * What the editor holds for each open file, when it differs from what was
     * read off disk. Kept here rather than in the state because every keystroke
     * would otherwise recompose the whole workspace; the state carries only the
     * dirty flag, which changes once per file rather than once per character.
     */
    private val pendingText = mutableMapOf<File, String>()

    private var descriptorJob: Job? = null
    private var buildJob: Job? = null
    private var installJob: Job? = null
    private var analysisJob: Job? = null
    private var signatureJob: Job? = null

    /**
     * How far the platform download got, kept outside the prompt's state so
     * that dismissing the prompt and opening it again shows the download still
     * running rather than an untouched Download button.
     */
    private var platformProgress: InstallProgress? = null

    fun open(projectDir: File) {
        if (rootNode?.file == projectDir) return
        val root = FileNode(projectDir, isDirectory = true, depth = 0)
        rootNode = root
        pendingText.clear()
        _state.value = WorkspaceUiState(
            projectName = projectDir.name,
            projectRoot = projectDir,
            expandedPaths = setOf(projectDir.absolutePath),
        )
        rebuildTree()
        installCompletions(projectDir)

        // The tree does not need the descriptor and building cannot start
        // without it, so the two are not sequenced: the files appear
        // immediately and the Build button waits on the job if it has to.
        descriptorJob = viewModelScope.launch {
            when (val result = projects.openProject(projectDir)) {
                is AppResult.Success -> {
                    project = result.value
                    _state.update { it.copy(projectName = result.value.name) }
                }
                // Left null. Editing a directory that is not an AIDE-OS project
                // is legitimate; building it is what is not, and build() says so.
                is AppResult.Failure -> Unit
            }
        }
    }

    /**
     * Points the editor's completion at this project, if anything can serve it.
     *
     * Cleared rather than left stale when it cannot: a service built for the
     * previous project would answer with that project's types, which is worse
     * than an empty list because it looks like it worked.
     */
    private fun installCompletions(projectDir: File) {
        // Without a classpath first, so the editor has intelligence for
        // platform types immediately. A cold dependency resolve is a minute of
        // network, and completion on android.* should not wait for it.
        languages.completionSource = languageServices.forProject(projectDir)
            ?.let(::JavaCompletionSource)

        // Then again with it, once the descriptor and the resolve are done. The
        // second service replaces the first: a warm compiler's symbol table
        // cannot be extended, so a wider classpath means a new one.
        viewModelScope.launch {
            descriptorJob?.join()
            val resolved = project ?: return@launch
            if (resolved.dependencies.isEmpty()) return@launch

            val classpath = runCatching { dependencies.classpathFor(resolved) }.getOrNull()
            if (classpath.isNullOrEmpty() || _state.value.projectRoot != projectDir) return@launch

            languages.completionSource = languageServices.forProject(projectDir, classpath)
                ?.let(::JavaCompletionSource)
            Log.i(TAG, "language service rebuilt with ${classpath.size} dependency jars")

            // Anything already on screen was analysed against the narrower
            // classpath and is now wrong -- most visibly, every androidx.*
            // import reported as unresolved.
            _state.value.active?.let { active ->
                analyse(active.file, pendingText[active.file] ?: active.document.text)
            }
        }
    }

    fun toggle(node: FileNode) {
        if (!node.isDirectory) {
            openDocument(node.file)
            return
        }
        val path = node.file.absolutePath
        _state.update { current ->
            val expanded = current.expandedPaths.toMutableSet()
            if (!expanded.remove(path)) expanded.add(path)
            current.copy(expandedPaths = expanded)
        }
        rebuildTree()
    }

    /** Opens [file] in a tab, or brings its tab to the front if it is open. */
    fun openDocument(file: File) {
        if (_state.value.openFiles.any { it.file == file }) {
            selectDocument(file)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(openingFile = file, documentError = null) }

            when (val result = documents.open(file)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        // Guard against two opens racing: whichever read
                        // finishes second must not append a duplicate tab.
                        openFiles = if (it.openFiles.any { open -> open.file == file }) {
                            it.openFiles
                        } else {
                            it.openFiles + OpenFile(result.value)
                        },
                        activeFile = file,
                        openingFile = null,
                    )
                }
                is AppResult.Failure -> _state.update {
                    it.copy(openingFile = null, documentError = result.error.message)
                }
            }
        }
    }

    fun selectDocument(file: File) {
        if (_state.value.activeFile == file) return
        signatureJob?.cancel()
        // The hint describes a caret in the outgoing file; keeping it would
        // caption the incoming one with the wrong call.
        _state.update {
            it.copy(
                activeFile = file,
                documentError = null,
                analysis = it.analysis.copy(signature = null),
            )
        }
    }

    /**
     * Closes a tab, saving it first.
     *
     * Saving rather than prompting: there is no separate "discard" story yet,
     * and losing an edit to a stray tap on a small × is far worse than a save
     * the user did not explicitly ask for. Undo still lives in the file.
     */
    fun closeDocument(file: File) {
        viewModelScope.launch {
            saveIfDirty(file)
            pendingText -= file

            _state.update { current ->
                val remaining = current.openFiles.filterNot { it.file == file }
                val nextActive = when {
                    current.activeFile != file -> current.activeFile
                    // The neighbour to the left, which is where the eye already
                    // is after closing something.
                    else -> {
                        val index = current.openFiles.indexOfFirst { it.file == file }
                        remaining.getOrNull((index - 1).coerceAtLeast(0))?.file
                    }
                }
                current.copy(
                    openFiles = remaining,
                    activeFile = nextActive,
                    documentError = null,
                )
            }
        }
    }

    /**
     * Called by the editor widget on every content change, including the one it
     * causes itself when a buffer is set -- which is why identical text is not
     * an edit.
     */
    fun onTextChanged(text: String) {
        val active = _state.value.active ?: return
        if (active.file !in pendingText && text == active.document.text) return
        pendingText[active.file] = text
        if (!active.isDirty) markDirty(active.file, dirty = true)
        analyse(active.file, text)
    }

    /**
     * Runs the language service over the buffer, a beat after typing stops.
     *
     * Debounced rather than throttled, and the previous request is cancelled
     * rather than allowed to finish: mid-word, a diagnostic is almost always
     * "cannot find symbol" for an identifier the user is halfway through
     * typing. Showing that and taking it back a keystroke later is worse than
     * showing nothing, and the compiler time spent producing it is wasted.
     *
     * The service serialises requests internally, so a cancelled job that has
     * already reached the compiler still has to finish there before the next
     * one starts. Cancelling early is what keeps that queue from growing.
     */
    private fun analyse(file: File, text: String) {
        val root = _state.value.projectRoot ?: return
        val service = languageServices.forProject(root) ?: return
        if (file.extension != "java") return

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            delay(ANALYSIS_DEBOUNCE_MILLIS)
            _state.update { it.copy(analysis = it.analysis.copy(isRunning = true)) }

            // Logged, not swallowed. A language service that fails silently
            // looks exactly like a clean file, and the first version of this
            // hid a NoSuchMethodError for as long as it took to notice that no
            // diagnostic had ever appeared. Analysis is still best-effort --
            // the editor must survive a broken compiler -- but not invisible.
            val diagnostics = try {
                service.diagnostics(file, text)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "analysis of ${file.name} failed", failure)
                null
            }

            // A cancelled job must not publish: by now the state may describe a
            // different file entirely.
            if (diagnostics == null || !isActive) return@launch
            _state.update { current ->
                current.copy(
                    analysis = AnalysisUiState(
                        file = file,
                        diagnostics = diagnostics,
                        isRunning = false,
                        // Carried across: the caret has not moved just because
                        // the file was re-analysed, and dropping it here would
                        // make the hint blink on every pause in typing.
                        signature = current.analysis.signature,
                    ),
                )
            }
        }
    }

    /**
     * Asks what call the caret is inside, and puts the answer in the state.
     *
     * Debounced harder than it looks like it needs to be. The caret moves on
     * every keystroke as well as every tap, so an undebounced hint would run a
     * compilation per character typed -- the same work as diagnostics, twice.
     * A hint that appears a beat after you stop moving is the correct
     * behaviour anyway; one that flickers on every character is not.
     */
    fun onCursorMoved(offset: Int) {
        val active = _state.value.active ?: return
        val root = _state.value.projectRoot ?: return
        if (active.file.extension != "java") return
        val service = languageServices.forProject(root) ?: return

        signatureJob?.cancel()
        signatureJob = viewModelScope.launch {
            delay(SIGNATURE_DEBOUNCE_MILLIS)
            val text = pendingText[active.file] ?: active.document.text
            val signature = try {
                service.signatureAt(active.file, text, offset)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "signature lookup failed", failure)
                null
            }
            if (!isActive) return@launch
            _state.update { it.copy(analysis = it.analysis.copy(signature = signature)) }
        }
    }

    /**
     * Jumps to the declaration of whatever is at [offset] in the active file.
     *
     * Opens the declaring file first when it is a different one, then jumps --
     * the two are sequenced rather than fired together because the jump is a
     * cursor move inside a document that has to exist first.
     */
    fun goToDefinition(offset: Int) {
        val active = _state.value.active ?: return
        val root = _state.value.projectRoot ?: return
        val service = languageServices.forProject(root) ?: return

        viewModelScope.launch {
            val text = pendingText[active.file] ?: active.document.text
            val target = try {
                service.definition(active.file, text, offset)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Log.w(TAG, "go-to-definition failed", failure)
                null
            }
            if (target == null) {
                _events.send(WorkspaceEvent.Notice("No definition found."))
                return@launch
            }

            // Opening a tab that is already open just selects it, so this is
            // unconditional; the screen holds the jump until that tab is the
            // one actually showing.
            openDocument(File(root, target.file.path))
            _jumps.trySend(EditorJump(target.file, target.line, target.column))
        }
    }

    fun save() {
        viewModelScope.launch {
            val file = _state.value.activeFile ?: return@launch
            saveIfDirty(file, announce = true)
        }
    }

    /** Writes every modified tab. Used before a build, which reads the disk. */
    private suspend fun saveAll() {
        _state.value.openFiles.filter { it.isDirty }.forEach { saveIfDirty(it.file) }
    }

    private suspend fun saveIfDirty(file: File, announce: Boolean = false) {
        val open = _state.value.openFiles.firstOrNull { it.file == file } ?: return
        val text = pendingText[file] ?: return

        when (val result = documents.save(open.document, text)) {
            is AppResult.Success -> {
                pendingText -= file
                // The document's own text moves with it, so the file on disk and
                // the state agree about what "unmodified" means.
                _state.update { current ->
                    current.copy(
                        openFiles = current.openFiles.map {
                            if (it.file == file) {
                                it.copy(document = it.document.copy(text = text), isDirty = false)
                            } else {
                                it
                            }
                        },
                    )
                }
                if (announce) _events.send(WorkspaceEvent.Notice("${open.name} saved."))
            }
            is AppResult.Failure -> _events.send(WorkspaceEvent.Notice(result.error.message))
        }
    }

    private fun markDirty(file: File, dirty: Boolean) {
        _state.update { current ->
            current.copy(
                openFiles = current.openFiles.map {
                    if (it.file == file) it.copy(isDirty = dirty) else it
                },
            )
        }
    }

    fun openSearch() {
        _state.update { it.copy(isSearchOpen = true) }
    }

    fun closeSearch() {
        _state.update { it.copy(isSearchOpen = false) }
    }

    fun build() {
        if (_state.value.build.isRunning) return
        buildJob = viewModelScope.launch {
            // A tap can beat the descriptor read -- the screen opened a moment
            // ago. Waiting for it is better than telling someone their project
            // is not one.
            descriptorJob?.join()

            // Before the checks below, not after: they can all refuse, and a
            // refused build must still have written the buffers. The user
            // asked for their work to be built; saving it is the part that can
            // always be honoured.
            saveAll()

            val project = project ?: run {
                _events.send(
                    WorkspaceEvent.Notice(
                        "This folder has no ${Project.DESCRIPTOR_NAME}, so there is nothing to build.",
                    ),
                )
                return@launch
            }
            builder.missingNativeToolchain(project)?.let { component ->
                // Offered before the build starts rather than after it fails,
                // for the same reason the platform is: the refusal names a
                // download, and a message naming a download with no way to
                // start it is a dead end.
                val megabytes = component.archiveBytes / (1024 * 1024)
                offerComponentInstall(
                    component = component,
                    rationale = "This project has C or C++ in src/main/cpp. " +
                        "Compiling it needs clang, which is about $megabytes MB " +
                        "to download and roughly ${component.installedBytes / (1024 * 1024)} MB " +
                        "once installed.",
                )
                return@launch
            }
            if (!builder.isPlatformInstalled()) {
                offerPlatformInstall()
                return@launch
            }
            runBuild(project)
        }
    }

    private suspend fun runBuild(project: Project) {
        try {
            _state.update {
                it.copy(isBuildPanelOpen = true, build = BuildUiState(isRunning = true))
            }
            builder.build(project).collect(::onBuildEvent)
        } finally {
            _state.update {
                it.copy(
                    build = it.build.copy(
                        isRunning = false,
                        stage = null,
                        outcome = it.build.outcome ?: "Build stopped.",
                    ),
                )
            }
        }
    }

    fun stopBuild() {
        buildJob?.cancel()
    }

    /**
     * Shows or hides the tool dock.
     *
     * The dock used to open only when a build started, which made sense while
     * it held nothing but build output. It now holds the git panel too, and
     * requiring a build before a commit is not a workflow anyone would choose.
     */
    fun toggleToolPanel() {
        _state.update { it.copy(isBuildPanelOpen = !it.isBuildPanelOpen) }
    }

    fun closeBuildPanel() {
        _state.update { it.copy(isBuildPanelOpen = false) }
    }

    /** Opens the file a diagnostic points at. Paths are project-relative. */
    fun openDiagnostic(diagnostic: Diagnostic) {
        val root = project?.rootDir ?: rootNode?.file ?: return
        val file = diagnostic.file ?: return
        openDocument(File(root, file.path))
        _jumps.trySend(EditorJump(file, diagnostic.line, diagnostic.column))
    }

    /**
     * Where the cursor has been asked to go next.
     *
     * A one-shot: the screen consumes it once the editor has that file showing
     * and moves the cursor. State would re-fire the jump on every recomposition,
     * dragging the user back from wherever they had scrolled to.
     *
     * Shared by diagnostics and go-to-definition because the hard part is the
     * same for both -- waiting for the tab to actually be the one on screen --
     * and doing it twice would mean two ways to land in the wrong buffer.
     */
    private val _jumps = Channel<EditorJump>(Channel.CONFLATED)
    val jumps: Flow<EditorJump> = _jumps.receiveAsFlow()

    private suspend fun onBuildEvent(event: BuildEvent) {
        when (event) {
            is BuildEvent.StageStarted ->
                _state.update { it.copy(build = it.build.copy(stage = event.stage)) }

            is BuildEvent.StageCompleted -> _state.update {
                val line = "${event.stage.displayName} — ${event.durationMillis} ms"
                it.copy(build = it.build.copy(log = it.build.log + line))
            }

            is BuildEvent.Note -> _state.update {
                it.copy(build = it.build.copy(log = it.build.log + event.message))
            }

            is BuildEvent.DiagnosticReported -> _state.update {
                it.copy(build = it.build.copy(diagnostics = it.build.diagnostics + event.diagnostic))
            }

            is BuildEvent.Finished -> onBuildFinished(event.result)
        }
    }

    private suspend fun onBuildFinished(result: BuildResult) {
        _state.update {
            it.copy(
                build = it.build.copy(
                    isRunning = false,
                    stage = null,
                    // The result's set is authoritative: it is the whole build's,
                    // and the streamed ones were only there to arrive early.
                    diagnostics = result.diagnostics,
                    succeeded = result.succeeded,
                    outcome = when (result) {
                        is BuildResult.Success -> "Built in ${result.durationMillis} ms."
                        is BuildResult.Failure -> result.message
                    },
                ),
            )
        }
        if (result is BuildResult.Success) install(result.apk)
    }

    /**
     * Hands the APK to the platform installer.
     *
     * Collected on the ViewModel's scope, not the screen's: the user is about to
     * leave for the system's confirmation dialog, and a flow tied to the
     * composition would be cancelled -- abandoning the session -- the moment
     * they did.
     */
    private suspend fun install(apk: File) {
        installer.install(apk).collect { status ->
            val install = when (status) {
                is InstallStatus.NeedsConfirmation -> {
                    _events.send(WorkspaceEvent.LaunchNow(status.confirmation))
                    InstallUiState("Waiting for you to confirm the install.")
                }

                InstallStatus.Installed -> InstallUiState("Installed.")

                is InstallStatus.Failed -> InstallUiState(status.message, status.settings)
            }
            _state.update { it.copy(build = it.build.copy(install = install)) }
        }
    }

    /** Shows what has to be downloaded before this device can build anything. */
    fun offerPlatformInstall() = offerComponentInstall(
        component = ToolchainComponent.ANDROID_PLATFORM,
        rationale = "Building needs android.jar, which cannot be shipped inside " +
            "AIDE-OS. It is downloaded once, from Google.",
    )

    /**
     * Shows the download prompt for any component.
     *
     * Generalised from the platform-only version when M7 arrived. A build that
     * refuses for a missing toolchain and offers no way to get it is not a
     * feature the user can reach -- and the C/C++ toolchain is the first
     * component that is *not* Google's, so the licence half has to be optional
     * rather than assumed. Asking someone to accept Google's terms in order to
     * compile Apache-licensed LLVM would be wrong on its own.
     */
    fun offerComponentInstall(component: ToolchainComponent, rationale: String) {
        if (_state.value.platform != null) return
        viewModelScope.launch {
            val text = if (component.requiresSdkLicense) {
                withContext(dispatchers.io) { toolchain.licenseText() }
            } else {
                ""
            }
            _state.update {
                it.copy(
                    platform = PlatformUiState(
                        component = component,
                        licenseText = text,
                        // Nothing to accept means nothing to gate on: the
                        // button says "Download" and no acceptance is recorded.
                        licenseAccepted = !component.requiresSdkLicense ||
                            toolchain.license.isAccepted(),
                        rationale = rationale,
                        progress = platformProgress,
                    ),
                )
            }
        }
    }

    fun acceptSdkLicense() {
        if (_state.value.platform == null) return
        viewModelScope.launch {
            withContext(dispatchers.io) { toolchain.license.accept() }
            _state.update { it.copy(platform = it.platform?.copy(licenseAccepted = true)) }
        }
    }

    fun installPlatform() {
        if (installJob?.isActive == true) return
        val platform = _state.value.platform ?: return

        installJob = viewModelScope.launch {
            toolchain.install(platform.component).collect { progress ->
                platformProgress = progress
                _state.update { it.copy(platform = it.platform?.copy(progress = progress)) }
                if (progress is InstallProgress.Installed) {
                    platformProgress = null
                    _state.update { it.copy(platform = null) }
                    _events.send(WorkspaceEvent.Notice("${platform.component.displayName} installed."))
                    // What the user asked for was a build; the download was the
                    // toll. Starting it saves them tapping Build again.
                    build()
                }
            }
        }
    }

    fun dismissPlatformInstall() {
        // Only the prompt closes. A download in flight keeps going -- 63 MB is
        // not something to throw away because a dialog was dismissed.
        _state.update { it.copy(platform = null) }
    }

    /**
     * Rebuilds the flattened tree off the main thread. Directory listing is
     * cheap per level but a deep project has many levels, and this runs on
     * every expand/collapse.
     */
    private fun rebuildTree() {
        val root = rootNode ?: return
        viewModelScope.launch {
            val expanded = _state.value.expandedPaths
            val flattened = withContext(dispatchers.io) { flatten(root, expanded) }
            _state.update { it.copy(visibleNodes = flattened) }
        }
    }

    private fun flatten(root: FileNode, expanded: Set<String>): List<FileNode> {
        val out = mutableListOf<FileNode>()
        fun walk(node: FileNode) {
            out += node
            if (node.isDirectory && node.file.absolutePath in expanded) {
                ProjectFiles.childrenOf(node).forEach(::walk)
            }
        }
        walk(root)
        return out
    }

    /**
     * The warm compiler is a symbol table's worth of platform classes. Nothing
     * else drops it, and the editor is the only thing that wanted it.
     */
    override fun onCleared() {
        languages.completionSource = null
        languageServices.release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "WorkspaceViewModel"

        /**
         * Long enough to sit through a word, short enough that pausing to think
         * shows the errors. Tuned against a warm request costing ~80 ms: at this
         * delay a normal typing burst produces one compile, not twelve.
         */
        const val ANALYSIS_DEBOUNCE_MILLIS = 350L

        /** Shorter than analysis: a hint is worth less the later it lands. */
        const val SIGNATURE_DEBOUNCE_MILLIS = 200L
    }
}