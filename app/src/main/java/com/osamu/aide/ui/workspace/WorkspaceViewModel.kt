package com.osamu.aide.ui.workspace

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.core.fs.FileNode
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.ProjectFiles
import com.osamu.aide.core.fs.ProjectRepository
import com.osamu.aide.editor.DocumentStore
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

data class WorkspaceUiState(
    val projectName: String = "",
    /** Flattened visible tree: only expanded directories contribute children. */
    val visibleNodes: List<FileNode> = emptyList(),
    val expandedPaths: Set<String> = emptySet(),
    val selectedFile: File? = null,
    /** The open file, or null while it is being read or could not be. */
    val document: SourceDocument? = null,
    val documentError: String? = null,
    val isDocumentDirty: Boolean = false,
    val build: BuildUiState = BuildUiState(),
    val isBuildPanelOpen: Boolean = false,
    val platform: PlatformUiState? = null,
)

class WorkspaceViewModel(
    private val dispatchers: DispatcherProvider,
    private val projects: ProjectRepository,
    private val documents: DocumentStore,
    private val builder: ProjectBuilder,
    private val toolchain: ToolchainManager,
    private val installer: ApkInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val _events = Channel<WorkspaceEvent>(Channel.BUFFERED)
    val events: Flow<WorkspaceEvent> = _events.receiveAsFlow()

    private var rootNode: FileNode? = null
    private var project: Project? = null

    /**
     * What the editor widget currently holds, when it differs from what was
     * read off disk. Kept here rather than in the state because every keystroke
     * would otherwise recompose the whole workspace.
     */
    private var pendingText: String? = null

    private var descriptorJob: Job? = null
    private var buildJob: Job? = null
    private var installJob: Job? = null

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
        _state.value = WorkspaceUiState(
            projectName = projectDir.name,
            expandedPaths = setOf(projectDir.absolutePath),
        )
        rebuildTree()

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

    /** Opens [file] in the editor, saving whatever was open before it. */
    fun openDocument(file: File) {
        if (_state.value.document?.file == file) return
        viewModelScope.launch {
            saveIfDirty()
            _state.update {
                it.copy(
                    selectedFile = file,
                    document = null,
                    documentError = null,
                    isDocumentDirty = false,
                )
            }
            pendingText = null

            when (val result = documents.open(file)) {
                is AppResult.Success -> _state.update { it.copy(document = result.value) }
                is AppResult.Failure ->
                    _state.update { it.copy(documentError = result.error.message) }
            }
        }
    }

    /**
     * Called by the editor widget on every content change, including the one it
     * causes itself when a document is first set -- which is why identical text
     * is not an edit.
     */
    fun onTextChanged(text: String) {
        val document = _state.value.document ?: return
        if (pendingText == null && text == document.text) return
        pendingText = text
        if (!_state.value.isDocumentDirty) {
            _state.update { it.copy(isDocumentDirty = true) }
        }
    }

    fun save() {
        viewModelScope.launch { saveIfDirty(announce = true) }
    }

    private suspend fun saveIfDirty(announce: Boolean = false) {
        val document = _state.value.document ?: return
        val text = pendingText ?: return

        when (val result = documents.save(document, text)) {
            is AppResult.Success -> {
                pendingText = null
                // The document's own text moves with it, so the file on disk and
                // the state agree about what "unmodified" means.
                _state.update {
                    it.copy(document = document.copy(text = text), isDocumentDirty = false)
                }
                if (announce) _events.send(WorkspaceEvent.Notice("${document.name} saved."))
            }
            is AppResult.Failure -> _events.send(WorkspaceEvent.Notice(result.error.message))
        }
    }

    fun build() {
        if (_state.value.build.isRunning) return
        buildJob = viewModelScope.launch {
            // A tap can beat the descriptor read -- the screen opened a moment
            // ago. Waiting for it is better than telling someone their project
            // is not one.
            descriptorJob?.join()

            val project = project ?: run {
                _events.send(
                    WorkspaceEvent.Notice(
                        "This folder has no ${Project.DESCRIPTOR_NAME}, so there is nothing to build.",
                    ),
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
            // The compiler reads the file, not the widget. Building an unsaved
            // buffer puts every diagnostic on the wrong line, which is worse
            // than a build that takes a moment longer to start.
            saveIfDirty()

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

    fun closeBuildPanel() {
        _state.update { it.copy(isBuildPanelOpen = false) }
    }

    /** Opens the file a diagnostic points at. Paths are project-relative. */
    fun openDiagnostic(diagnostic: Diagnostic) {
        val root = project?.rootDir ?: rootNode?.file ?: return
        val file = diagnostic.file ?: return
        openDocument(File(root, file.path))
    }

    private suspend fun onBuildEvent(event: BuildEvent) {
        when (event) {
            is BuildEvent.StageStarted ->
                _state.update { it.copy(build = it.build.copy(stage = event.stage)) }

            is BuildEvent.StageCompleted -> _state.update {
                val line = "${event.stage.displayName} — ${event.durationMillis} ms"
                it.copy(build = it.build.copy(log = it.build.log + line))
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
    fun offerPlatformInstall() {
        if (_state.value.platform != null) return
        viewModelScope.launch {
            val component = ToolchainComponent.ANDROID_PLATFORM
            val text = withContext(dispatchers.io) { toolchain.licenseText() }
            _state.update {
                it.copy(
                    platform = PlatformUiState(
                        component = component,
                        licenseText = text,
                        licenseAccepted = toolchain.license.isAccepted(),
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
}
