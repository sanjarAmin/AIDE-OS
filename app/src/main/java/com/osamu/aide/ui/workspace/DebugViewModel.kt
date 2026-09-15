package com.osamu.aide.ui.workspace

import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.osamu.aide.core.common.DispatcherProvider
import com.osamu.aide.debug.PendingDebuggees
import com.osamu.aide.debugger.DebugController
import com.osamu.aide.debugger.DebugState
import com.osamu.aide.debugger.SourceKeys
import com.osamu.aide.debugger.SourceLine
import com.osamu.aide.debugger.StartupGate
import com.osamu.aide.engine.api.DebuggerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A breakpoint as the editor holds it: a file, and a 1-based line. */
data class FileBreakpoint(val file: File, val line: Int)

data class DebugUiState(
    val session: DebugState = DebugState.Idle,
    val breakpoints: Set<FileBreakpoint> = emptySet(),
    /**
     * Where the selected frame is in the project, when it is in the project.
     *
     * Resolved here rather than by the screen because it can take a walk of
     * the source tree, and a frame in framework code has no file at all.
     */
    val executionPoint: FileBreakpoint? = null,
) {
    val isActive: Boolean
        get() = session is DebugState.Attaching || session is DebugState.Running || session is DebugState.Stopped

    fun breakpointLinesIn(file: File?): Set<Int> =
        if (file == null) emptySet() else breakpoints.filter { it.file == file }.mapTo(mutableSetOf()) { it.line }
}

/**
 * The debug session behind the Debug tab.
 *
 * Its own view model, like the terminal's and logcat's, because it owns
 * something with a lifetime of its own -- a socket into another app, and threads
 * in that app it may have suspended. Leaving the screen detaches, which resumes
 * them; `DebugController.detach` has why that matters.
 *
 * **Breakpoints outlive sessions and the workspace.** They are set before
 * Debug is tapped, kept when the app exits, placed again on the next session,
 * saved per project by [BreakpointStore], and moved with the code around them
 * as it is edited ([BreakpointLines]).
 */
class DebugViewModel(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    /**
     * The controller's own scope, **not a child of viewModelScope.**
     *
     * viewModelScope is cancelled before [onCleared] runs, so a detach launched
     * from it would never happen -- and a detach is what resumes threads the
     * debugger suspended. Closing the IDE on a breakpoint would leave the app
     * frozen. Off the main dispatcher too: attaching connects a socket, and
     * each command waits on a round trip to another process.
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val controller = DebugController(controllerScope)

    private val breakpoints = MutableStateFlow<Set<FileBreakpoint>>(emptySet())
    private val keys = mutableMapOf<FileBreakpoint, SourceLine>()
    private val executionPoint = MutableStateFlow<FileBreakpoint?>(null)
    private var projectRoot: File? = null
    private val store = BreakpointStore(File(context.filesDir, "breakpoints"))

    /** The last text seen for each edited file: the "before" of the next edit. */
    private val texts = HashMap<File, String>()

    val state: StateFlow<DebugUiState> = combine(
        controller.state,
        breakpoints,
        executionPoint,
    ) { session, set, point -> DebugUiState(session, set, point) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DebugUiState())

    init {
        viewModelScope.launch {
            var wasStopped = false
            controller.state.collect { session ->
                val stopped = session as? DebugState.Stopped
                val source = stopped?.frame?.source
                executionPoint.value = source?.let { withContext(dispatchers.io) { resolve(it) } }
                if (stopped != null && !wasStopped) {
                    controller.raiseFlag(DebuggerRequest.COME_FORWARD_FIELD)
                }
                wasStopped = stopped != null
                if (session is DebugState.Ended || session is DebugState.Idle) releaseDebuggee()
            }
        }
    }

    /** The reference that keeps the debuggee awake, while there is a session. */
    private var debuggeeHold: ContentProviderClient? = null

    /**
     * Holds the app under debug, so Android does not freeze it while the IDE
     * is in front.
     *
     * **A frozen debuggee answers nothing.** Found exactly that way: Step
     * pressed in the IDE, the app behind it already frozen, and the request
     * waiting for a reply that could not come. The app binds the IDE's service
     * in turn, from its generated agent. `debugger/FINDINGS.md` section 9.
     *
     * **Unstable, deliberately.** A stable reference to a provider gets the
     * *client* killed when the provider's process dies -- and here the client is
     * the IDE, and the provider's process is an app the user is debugging, which
     * dies all the time.
     */
    private fun holdDebuggee(applicationId: String) {
        releaseDebuggee()
        debuggeeHold = runCatching {
            context.contentResolver.acquireUnstableContentProviderClient(
                DebuggerRequest.agentAuthority(applicationId),
            )
        }.getOrNull()
    }

    private fun releaseDebuggee() {
        debuggeeHold?.let { runCatching { it.close() } }
        debuggeeHold = null
    }

    fun open(root: File) {
        if (projectRoot == root) return
        projectRoot = root
        texts.clear()
        viewModelScope.launch {
            // Each one needs its file's package for a key, and a breakpoint in
            // a file that has since been deleted is dropped rather than kept
            // pointing at nothing.
            val restored = withContext(dispatchers.io) {
                store.load(root).mapNotNull { breakpoint ->
                    val text = runCatching { breakpoint.file.readText() }.getOrNull() ?: return@mapNotNull null
                    if (breakpoint.line > text.count { it == '\n' } + 1) return@mapNotNull null
                    breakpoint to SourceLine(SourceKeys.forSource(breakpoint.file.name, text), breakpoint.line)
                }
            }
            if (projectRoot != root) return@launch
            keys.clear()
            restored.forEach { (breakpoint, key) -> keys[breakpoint] = key }
            breakpoints.value = keys.keys.toSet()
            controller.setBreakpoints(keys.values.toSet())
        }
    }

    /**
     * The buffer of [file] is now [text]: moves its breakpoints with the edit.
     *
     * Called on every keystroke, so a file with no breakpoints costs a map
     * write and nothing else.
     */
    fun onEdited(file: File, text: String) {
        val before = texts.put(file, text)
        val inFile = breakpoints.value.filter { it.file == file }
        if (inFile.isEmpty()) return
        val previous = before ?: runCatching { file.readText() }.getOrNull() ?: return
        val lines = inFile.mapTo(HashSet()) { it.line }
        val moved = BreakpointLines.followEdit(lines, previous, text)
        if (moved == lines) return

        // The file's key is its package, which an edit rarely touches; taken
        // from the text anyway, since one that did would move them all.
        val key = SourceKeys.forSource(file.name, text)
        inFile.forEach { keys.remove(it) }
        moved.forEach { line -> keys[FileBreakpoint(file, line)] = SourceLine(key, line) }
        breakpoints.value = keys.keys.toSet()
        persist()
        controller.setBreakpoints(keys.values.toSet())
    }

    private fun persist() {
        val root = projectRoot ?: return
        val snapshot = breakpoints.value
        viewModelScope.launch(dispatchers.io) { store.save(root, snapshot) }
    }

    /** Sets or clears a breakpoint on a line, as a tap on the line number does. */
    fun toggleBreakpoint(file: File, line: Int) {
        val breakpoint = FileBreakpoint(file, line)
        viewModelScope.launch {
            if (breakpoint in breakpoints.value) {
                keys.remove(breakpoint)
                breakpoints.update { it - breakpoint }
            } else {
                // The key needs the file's package, which is in its text. Read
                // from disk: a Debug saves every buffer before it builds, and
                // the package line is the part of a file least likely to be
                // mid-edit.
                val key = withContext(dispatchers.io) {
                    runCatching { SourceKeys.forSource(file.name, file.readText()) }.getOrNull()
                } ?: return@launch
                keys[breakpoint] = SourceLine(key, line)
                breakpoints.update { it + breakpoint }
            }
            persist()
            controller.setBreakpoints(keys.values.toSet())
        }
    }

    fun removeBreakpoint(breakpoint: FileBreakpoint) {
        if (breakpoint in breakpoints.value) toggleBreakpoint(breakpoint.file, breakpoint.line)
    }

    /**
     * Starts the installed app and attaches to it.
     *
     * The expectation is registered **before** the launch, because the app
     * asks the moment its process starts; registering after would race the
     * very startup the handshake exists to hold.
     */
    fun start(applicationId: String, port: Int) {
        if (state.value.isActive) controller.detach("Replaced by a new session.")
        PendingDebuggees.expect(applicationId)
        val launch = context.packageManager.getLaunchIntentForPackage(applicationId)
        if (launch == null) {
            PendingDebuggees.forget(applicationId)
            // Reported through the session so it lands in the Debug tab, where
            // the user is looking, rather than a snackbar that is gone.
            controller.report("$applicationId has no launcher activity to start.")
            return
        }
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        controllerScope.launch {
            val attached = controller.attach(
                port = port,
                breakpoints = keys.values.toSet(),
                gate = StartupGate(DebuggerRequest.AGENT_SIGNATURE, DebuggerRequest.RELEASE_FIELD),
            )
            if (attached) {
                withContext(dispatchers.main) { holdDebuggee(applicationId) }
            } else {
                PendingDebuggees.forget(applicationId)
            }
        }
    }

    fun resume() = controller.resume()
    fun stepOver() = controller.stepOver()
    fun stepInto() = controller.stepInto()
    fun stepOut() = controller.stepOut()
    fun selectFrame(index: Int) = controller.selectFrame(index)
    fun toggleExpanded(objectId: Long) = controller.toggleExpanded(objectId)
    fun stop() = controller.detach()

    /**
     * The project file a source key names, if the project has it.
     *
     * Breakpoints the user set are found without looking. Anything else -- a
     * step into another file -- walks the source tree once for files with that
     * name and checks each one's package, since a name alone is ambiguous
     * across packages.
     */
    private fun resolve(line: SourceLine): FileBreakpoint? {
        keys.entries.firstOrNull { it.value.key == line.key }?.let { (breakpoint, _) ->
            return FileBreakpoint(breakpoint.file, line.line)
        }
        val root = projectRoot ?: return null
        val name = line.key.substringAfterLast('/')
        return root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.name == name }
            .firstOrNull { runCatching { SourceKeys.forSource(it.name, it.readText()) == line.key }.getOrDefault(false) }
            ?.let { FileBreakpoint(it, line.line) }
    }

    override fun onCleared() {
        // Resumes anything suspended. An IDE closed mid-session must not leave
        // the app it was debugging frozen on a breakpoint. The scope goes once
        // the detach has finished, not before.
        releaseDebuggee()
        controllerScope.launch {
            controller.detachNow()
            controllerScope.cancel()
        }
    }
}
