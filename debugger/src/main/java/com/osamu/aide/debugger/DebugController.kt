package com.osamu.aide.debugger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One frame of a stopped thread, resolved to something a person can read. */
data class FrameView(
    val frameId: Long,
    val location: Location,
    /** `MainActivity.onCreate`, without a package: the package is in [sourceKey]. */
    val title: String,
    /** Where it is in source, or null for a frame with no source here. */
    val source: SourceLine?,
)

/** A variable or field, rendered, and expandable when it is an object. */
data class VariableView(
    val name: String,
    val type: String,
    val value: String,
    /** The object to expand into, or null for a primitive, a string, or null. */
    val objectId: Long? = null,
)

sealed interface DebugState {

    data object Idle : DebugState

    data class Attaching(val message: String) : DebugState

    /** Attached, and nothing is stopped. [message] says what it is waiting for. */
    data class Running(
        val message: String,
        /** A step is in flight: running, but about to stop again on its own. */
        val stepping: Boolean = false,
    ) : DebugState

    data class Stopped(
        val threadId: Long,
        val threadName: String,
        /** Innermost first. */
        val frames: List<FrameView>,
        val selectedFrame: Int,
        val variables: List<VariableView>,
        /** Fields of objects the user has opened, by object id. */
        val expanded: Map<Long, List<VariableView>> = emptyMap(),
        /** Why variables are empty, when they are. */
        val variablesNote: String? = null,
        /** Other threads that stopped while this one was on screen. */
        val waiting: Int = 0,
    ) : DebugState {
        val frame: FrameView? get() = frames.getOrNull(selectedFrame)
    }

    /** The session is over. The app may well still be running. */
    data class Ended(val message: String) : DebugState
}

/**
 * The code that holds a debug build's startup until the debugger is ready.
 *
 * Passed in rather than known here, because what the build puts into an app
 * is the build's contract and not the protocol's: this module speaks JDWP to
 * anything listening. Null for a debuggee with no gate.
 */
data class StartupGate(val classSignature: String, val releaseField: String)

/**
 * A debugging session as a screen sees it: breakpoints by source line, a
 * [state] to render, and the handful of things a person does to a stopped app.
 *
 * **Every command and every event is handled under one lock**, because each
 * of them reads and rewrites the same bookkeeping -- which requests exist,
 * which thread is on screen -- and JDWP gives no way to ask afterwards. A
 * breakpoint arriving while Resume is half-done would otherwise put a thread
 * on screen that Resume then lets go of.
 *
 * Breakpoints are resolved by **source file**, not class name. A line is
 * installed into every loaded class whose package and `SourceFile` match its
 * key, and each package that holds a breakpoint gets a `ClassPrepare` request
 * that holds the loading thread, so a class that loads later gets the
 * breakpoint before any of its code runs. `FINDINGS.md` sections 5 and 7.
 */
class DebugController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<DebugState>(DebugState.Idle)
    val state: StateFlow<DebugState> = _state.asStateFlow()

    private val lock = Mutex()
    private var session: DebugSession? = null
    private var events: Job? = null

    private var wanted: Set<SourceLine> = emptySet()
    private var gate: StartupGate? = null

    /** Breakpoint request id -> the source line it was placed for. */
    private val placed = mutableMapOf<Int, SourceLine>()

    /** (class, line) pairs already holding a breakpoint, so a class prepared twice gets one. */
    private val placedIn = mutableSetOf<Pair<Long, SourceLine>>()

    /** Dotted package -> its ClassPrepare request id. */
    private val prepareRequests = mutableMapOf<String, Int>()

    private val loaded = mutableMapOf<Long, LoadedClass>()
    private val sourceKeys = mutableMapOf<Long, String?>()
    private val methodsByClass = mutableMapOf<Long, List<MethodInfo>>()
    private val lineTables = mutableMapOf<Pair<Long, Long>, List<LineEntry>>()

    /** The step request in flight, and the thread it is for. */
    private var step: Pair<Int, Long>? = null

    /** Threads that stopped while another was on screen, oldest first. */
    private val queued = ArrayDeque<JdwpEvent.Stopped>()

    val isAttached: Boolean get() = session != null

    /**
     * Connects to a debuggee that is starting up, places [breakpoints], and
     * lets it go.
     *
     * Retried for [budgetMs] because the app is launched a moment before this
     * is called and opens its port some time after. Returns false, with
     * [state] saying why, if it never listened.
     */
    suspend fun attach(
        port: Int,
        breakpoints: Set<SourceLine>,
        gate: StartupGate? = null,
        budgetMs: Long = 15_000,
    ): Boolean = lock.withLock {
        if (session != null) return@withLock true
        wanted = breakpoints
        this.gate = gate
        _state.value = DebugState.Attaching("Waiting for the app to open its debug port…")

        val deadline = System.currentTimeMillis() + budgetMs
        var connected: DebugSession? = null
        var lastError: Throwable? = null
        while (connected == null && System.currentTimeMillis() < deadline) {
            try {
                connected = attachDebugger(port = port, connectTimeoutMs = 500)
            } catch (t: Throwable) {
                lastError = t
                delay(ATTACH_RETRY_MS)
            }
        }
        if (connected == null) {
            _state.value = DebugState.Ended(
                "The app never opened its debug port (${lastError?.message ?: "no answer"}). " +
                    "Was it built with Debug, and did it start?",
            )
            return@withLock false
        }
        session = connected

        _state.value = DebugState.Attaching("Placing breakpoints…")
        runCatching {
            connected.allClasses().forEach { loaded[it.typeId] = it }
            wanted.forEach { place(connected, it) }
        }.onFailure {
            _state.value = DebugState.Ended("Could not place breakpoints: ${it.message}")
            connected.close()
            session = null
            return@withLock false
        }

        // The event loop starts before the gate opens: the first thing the app
        // does once released may be to hit a breakpoint, and that event must
        // have somewhere to go.
        events = scope.launch { consume(connected) }
        gate?.let { release(connected, it) }
        _state.value = DebugState.Running(running())
        true
    }

    /** Replaces the set of breakpoints, placing and clearing the difference. */
    fun setBreakpoints(breakpoints: Set<SourceLine>) {
        scope.launch {
            lock.withLock {
                val previous = wanted
                wanted = breakpoints
                val live = session ?: return@withLock
                (previous - breakpoints).forEach { unplace(live, it) }
                (breakpoints - previous).forEach { place(live, it) }
                if (_state.value is DebugState.Running) _state.value = DebugState.Running(running())
            }
        }
    }

    fun resume() = command { live, stopped ->
        live.resumeThread(stopped.threadId)
        showNextOrRun(live)
    }

    fun stepOver() = stepWith(Jdwp.StepDepth.OVER)
    fun stepInto() = stepWith(Jdwp.StepDepth.INTO)
    fun stepOut() = stepWith(Jdwp.StepDepth.OUT)

    private fun stepWith(depth: Int) = command { live, stopped ->
        // **Step into skips the platform**, as Android Studio's does. Without
        // it, Step into on `scores.sumOf { ... }` in a Kotlin app went to
        // Arrays.java, then Object.java, then Arrays.java again -- code the
        // editor cannot open, three taps from the lambda the user wanted.
        // Only for INTO: over and out enter nothing new, and filtering them
        // would turn a step off the end of `onCreate` into a step that never
        // lands, since there is no user code for it to return to.
        val excluding = if (depth == Jdwp.StepDepth.INTO) STEP_INTO_EXCLUDES else emptyList()
        val request = live.requestStep(stopped.threadId, depth = depth, excluding = excluding)
        step = request to stopped.threadId
        live.resumeThread(stopped.threadId)
        _state.value = DebugState.Running("Stepping…", stepping = true)
    }

    /** Shows another frame's variables. */
    fun selectFrame(index: Int) = command { live, stopped ->
        val frame = stopped.frames.getOrNull(index) ?: return@command
        val (variables, note) = variablesOf(live, stopped.threadId, frame)
        _state.value = stopped.copy(selectedFrame = index, variables = variables, variablesNote = note)
    }

    /** Opens an object to show its fields, or closes it again. */
    fun toggleExpanded(objectId: Long) = command { live, stopped ->
        _state.value = if (objectId in stopped.expanded) {
            stopped.copy(expanded = stopped.expanded - objectId)
        } else {
            val fields = runCatching {
                live.fields(objectId).map { render(live, it.name, it.signature, it.value) }
            }.getOrElse { listOf(VariableView("", "", "Could not read: ${it.message}")) }
            stopped.copy(expanded = stopped.expanded + (objectId to fields))
        }
    }

    /**
     * Ends the session and lets the app carry on.
     *
     * `Dispose` rather than a closed socket: it resumes everything this
     * debugger suspended, and stopping a debugger must never be what freezes
     * the app.
     */
    fun detach(message: String = "Detached. The app keeps running.") {
        scope.launch { detachNow(message) }
    }

    /** [detach], finishing before it returns -- for a caller about to go away. */
    suspend fun detachNow(message: String = "Detached. The app keeps running.") {
        lock.withLock {
            val live = session ?: return@withLock
            events?.cancel()
            live.dispose()
            reset()
            _state.value = DebugState.Ended(message)
        }
    }

    /**
     * Ends a session that never got as far as attaching, with a reason.
     *
     * For failures before the controller is involved -- no launcher activity to
     * start -- so they are reported where the session's own failures are,
     * rather than somewhere the Debug tab does not show.
     */
    fun report(message: String) {
        scope.launch {
            lock.withLock {
                if (session == null) _state.value = DebugState.Ended(message)
            }
        }
    }

    private fun command(block: suspend (DebugSession, DebugState.Stopped) -> Unit) {
        scope.launch {
            lock.withLock {
                val live = session ?: return@withLock
                val stopped = _state.value as? DebugState.Stopped ?: return@withLock
                runCatching { block(live, stopped) }.onFailure { failure ->
                    if (failure is JdwpErrorException || failure is java.io.IOException) {
                        noteFailure(failure)
                    } else {
                        throw failure
                    }
                }
            }
        }
    }

    private fun noteFailure(failure: Throwable) {
        val message = "The app did not answer: ${failure.message}"
        _state.value = when (val current = _state.value) {
            is DebugState.Stopped -> current.copy(variablesNote = message)
            is DebugState.Running -> current.copy(message = message, stepping = false)
            else -> current
        }
    }

    /**
     * Sets one of the gate's static flags in the app -- the request to bring
     * the IDE forward, today.
     *
     * Allowed while threads are suspended, which is when it is wanted: a
     * static field is written by the VM on the debugger's behalf and needs no
     * thread of the app's to run.
     */
    fun raiseFlag(field: String) {
        scope.launch {
            lock.withLock {
                val live = session ?: return@withLock
                val gate = gate ?: return@withLock
                runCatching {
                    val agent = live.classesBySignature(gate.classSignature).firstOrNull() ?: return@runCatching
                    val flag = live.declaredFields(agent.typeId).firstOrNull { it.name == field } ?: return@runCatching
                    live.setStaticBoolean(agent.typeId, flag.id, true)
                }
            }
        }
    }

    private suspend fun consume(live: DebugSession) {
        try {
            live.events.collect { set ->
                lock.withLock {
                    // One unanswered round trip must not end the session: the
                    // app may only have been frozen for a moment, and every
                    // later event would otherwise have nowhere to go.
                    try {
                        handle(live, set)
                    } catch (e: java.io.IOException) {
                        noteFailure(e)
                    } catch (e: JdwpErrorException) {
                        noteFailure(e)
                    }
                }
            }
        } finally {
            lock.withLock {
                if (session === live) {
                    live.close()
                    reset()
                    _state.value = DebugState.Ended("The app exited, or closed the connection.")
                }
            }
        }
    }

    private suspend fun handle(live: DebugSession, set: JdwpEventSet) {
        // A thread is suspended once per event set, however many events in the
        // set it caused -- so it is resumed once, too.
        val toResume = mutableSetOf<Long>()
        for (event in set.events) {
            when (event) {
                is JdwpEvent.ClassPrepare -> {
                    if (event.requestId in prepareRequests.values) {
                        loaded[event.typeId] = LoadedClass(event.typeTag, event.typeId, event.signature, 0)
                        wanted.forEach { line -> placeInClass(live, event.typeId, line) }
                    }
                    // **Resumed whether or not the request is still ours.** One
                    // cleared a moment ago can still deliver an event already
                    // in flight, and its thread is just as suspended -- skip
                    // it and the app freezes on a class load, with nothing on
                    // screen to say why.
                    if (set.suspendPolicy != Jdwp.SuspendPolicy.NONE) toResume += event.threadId
                }

                is JdwpEvent.Stopped -> onStopped(live, event)

                is JdwpEvent.VmDeath -> {
                    live.close()
                    reset()
                    _state.value = DebugState.Ended("The app exited.")
                    return
                }

                is JdwpEvent.Unparsed -> Unit
            }
        }
        toResume.forEach { live.resumeThread(it) }
    }

    private suspend fun onStopped(live: DebugSession, event: JdwpEvent.Stopped) {
        // Whatever stopped the thread, a step still pending on it would stop it
        // again one line later -- which reads as the debugger ignoring Resume.
        step?.let { (request, thread) ->
            if (thread == event.threadId) {
                runCatching { live.clearStep(request) }
                step = null
            }
        }
        // **One suspension, however many events describe it.** A step that
        // lands on a line with a breakpoint arrives as a single event set
        // holding both a SingleStep and a Breakpoint for the same thread. Each
        // used to be taken as its own stop: the second was queued as "1 more
        // waiting", and Resume then showed it -- a stop for a thread that had
        // just been resumed. Found stepping through a Kotlin `sumOf` whose
        // lambda held a breakpoint. A thread cannot be stopped twice without
        // being resumed in between, so a second stop for a thread already shown
        // or queued is the same one.
        val shown = (_state.value as? DebugState.Stopped)?.threadId
        if (shown == event.threadId || queued.any { it.threadId == event.threadId }) return
        if (_state.value is DebugState.Stopped) {
            queued.addLast(event)
            val current = _state.value as DebugState.Stopped
            _state.value = current.copy(waiting = queued.size)
            return
        }
        show(live, event)
    }

    private suspend fun show(live: DebugSession, event: JdwpEvent.Stopped) {
        val frames = runCatching { live.frames(event.threadId) }.getOrDefault(emptyList())
            .take(MAX_FRAMES)
            .map { frameView(live, it) }
        val top = frames.firstOrNull()
        val (variables, note) = if (top == null) {
            emptyList<VariableView>() to "No frames."
        } else {
            variablesOf(live, event.threadId, top)
        }
        _state.value = DebugState.Stopped(
            threadId = event.threadId,
            threadName = runCatching { live.threadName(event.threadId) }.getOrDefault("thread"),
            frames = frames,
            selectedFrame = 0,
            variables = variables,
            variablesNote = note,
            waiting = queued.size,
        )
    }

    private suspend fun showNextOrRun(live: DebugSession) {
        val next = queued.removeFirstOrNull()
        if (next != null) show(live, next) else _state.value = DebugState.Running(running())
    }

    private suspend fun frameView(live: DebugSession, frame: Frame): FrameView {
        val classId = frame.location.classId
        val signature = loaded[classId]?.signature
            ?: runCatching { live.signature(classId) }.getOrDefault("L?;")
        val methods = methodsByClass.getOrPut(classId) {
            runCatching { live.methods(classId) }.getOrDefault(emptyList())
        }
        val method = methods.firstOrNull { it.id == frame.location.methodId }
        val key = keyOf(live, classId, signature)
        val line = lineTables.getOrPut(classId to frame.location.methodId) {
            runCatching { live.lineTable(classId, frame.location.methodId) }.getOrDefault(emptyList())
        }
            .filter { it.codeIndex <= frame.location.index }
            .maxByOrNull { it.codeIndex }
            ?.lineNumber
        return FrameView(
            frameId = frame.id,
            location = frame.location,
            title = "${readableType(signature)}.${method?.name ?: "?"}",
            source = if (key != null && line != null) SourceLine(key, line) else null,
        )
    }

    private suspend fun variablesOf(
        live: DebugSession,
        threadId: Long,
        frame: FrameView,
    ): Pair<List<VariableView>, String?> {
        val table = try {
            live.variableTable(frame.location.classId, frame.location.methodId)
        } catch (e: JdwpErrorException) {
            // Absent debug information: a release build, a framework method,
            // or native code. Worth saying, because an empty list looks like a
            // method with no variables.
            return emptyList<VariableView>() to "No variable information for this frame."
        }
        val live0 = table
            .filter { it.isLiveAt(frame.location.index) }
            // Compiler-generated slots -- Kotlin's `$i$a$-let` markers and the
            // like -- are not variables anyone wrote.
            .filterNot { it.name.startsWith("$") || it.name.isEmpty() }
            .sortedWith(compareBy({ it.name != "this" }, { it.slot }))
        if (live0.isEmpty()) return emptyList<VariableView>() to null
        val values = live.values(threadId, frame.frameId, live0)
        return live0.map { variable ->
            render(live, variable.name, variable.signature, values[variable.name] ?: JdwpValue.Void)
        } to null
    }

    private suspend fun render(
        live: DebugSession,
        name: String,
        signature: String,
        value: JdwpValue,
    ): VariableView = when (value) {
        is JdwpValue.Primitive -> VariableView(
            name = name,
            type = readableType(signature),
            value = if (value.value is Char) "'${value.value}'" else value.value.toString(),
        )

        is JdwpValue.Void -> VariableView(name, readableType(signature), "")

        is JdwpValue.Reference -> when {
            value.isNull -> VariableView(name, readableType(signature), "null")

            value.tag == Jdwp.Tag.STRING -> {
                val text = runCatching { live.stringValue(value.id) }.getOrDefault("?")
                VariableView(
                    name = name,
                    type = "String",
                    value = "\"" + text.take(MAX_STRING) + (if (text.length > MAX_STRING) "…" else "") + "\"",
                )
            }

            value.tag == Jdwp.Tag.ARRAY -> {
                val length = runCatching { live.arrayLength(value.id) }.getOrNull()
                val type = readableType(signature)
                VariableView(name, type, type.replaceFirst("[]", "[${length ?: "?"}]"))
            }

            else -> {
                // The runtime type, which is the interesting one: a field
                // declared `Runnable` holding a `MainActivity$1` should say so.
                val runtime = runCatching { readableType(live.signature(live.typeOf(value.id))) }
                    .getOrDefault(readableType(signature))
                VariableView(name, readableType(signature), runtime, objectId = value.id)
            }
        }
    }

    /** Places one source line into every loaded class it belongs to, and watches its package. */
    private suspend fun place(live: DebugSession, line: SourceLine) {
        val packagePath = line.key.substringBeforeLast('/', missingDelimiterValue = "")
        loaded.values
            .filter { it.signature.startsWith("L") && SourceKeys.packagePathOf(it.signature) == packagePath }
            .forEach { placeInClass(live, it.typeId, line) }

        val pattern = SourceKeys.packageOf(line.key).let { pkg ->
            // The default package has no prefix to match on, and `*` would
            // hold every class the app loads. The file's own name is the
            // narrowest thing that still catches `Main`, `MainKt` and their
            // lambdas.
            if (pkg.isEmpty()) line.key.substringBeforeLast('.') + "*" else "$pkg.*"
        }
        if (pattern !in prepareRequests) {
            prepareRequests[pattern] = live.requestClassPrepare(pattern)
        }
    }

    private suspend fun placeInClass(live: DebugSession, typeId: Long, line: SourceLine) {
        if ((typeId to line) in placedIn) return
        val signature = loaded[typeId]?.signature ?: return
        if (keyOf(live, typeId, signature) != line.key) return
        val typeTag = loaded[typeId]?.typeTag ?: Jdwp.Tag.CLASS
        val breakpoint = runCatching {
            live.breakpointInType(typeTag, typeId, line.line)
        }.getOrNull() ?: return
        placed[breakpoint.requestId] = line
        placedIn += typeId to line
    }

    private suspend fun unplace(live: DebugSession, line: SourceLine) {
        placed.filterValues { it == line }.keys.toList().forEach { request ->
            runCatching { live.clearBreakpoint(request) }
            placed.remove(request)
        }
        placedIn.removeAll { it.second == line }
        // A package with no breakpoints left stops holding every class it
        // loads: each of those holds costs a round trip, for nothing.
        val stillWatched = wanted.map { SourceKeys.packageOf(it.key) }.toSet()
        prepareRequests.keys
            .filter { pattern -> pattern.removeSuffix(".*") !in stillWatched && pattern.endsWith(".*") }
            .forEach { pattern ->
                prepareRequests.remove(pattern)?.let { runCatching { live.clearClassPrepare(it) } }
            }
    }

    private suspend fun keyOf(live: DebugSession, typeId: Long, signature: String): String? =
        sourceKeys.getOrPut(typeId) {
            runCatching { live.sourceFile(typeId) }.getOrNull()
                ?.let { SourceKeys.forClass(signature, it) }
        }

    /**
     * Opens the generated agent's gate, so the app's startup continues.
     *
     * A debuggee without the gate -- one not built by AIDE-OS, or one launched
     * without asking to be debugged -- simply has no such class loaded, which
     * is not an error.
     */
    private suspend fun release(live: DebugSession, gate: StartupGate) {
        runCatching {
            val agent = live.classesBySignature(gate.classSignature).firstOrNull() ?: return
            val field = live.declaredFields(agent.typeId).firstOrNull { it.name == gate.releaseField } ?: return
            live.setStaticBoolean(agent.typeId, field.id, true)
        }
    }

    private fun running(): String = when (val count = wanted.size) {
        0 -> "Running. Tap a line number to set a breakpoint."
        1 -> "Running, with 1 breakpoint."
        else -> "Running, with $count breakpoints."
    }

    private fun reset() {
        session = null
        events = null
        placed.clear()
        placedIn.clear()
        prepareRequests.clear()
        loaded.clear()
        sourceKeys.clear()
        methodsByClass.clear()
        lineTables.clear()
        step = null
        queued.clear()
    }

    private companion object {
        const val ATTACH_RETRY_MS = 150L
        const val MAX_FRAMES = 50
        const val MAX_STRING = 200

        /**
         * What Step into does not stop in: the runtime, the platform, and the
         * libraries every app carries. AndroidX is here too -- it arrives as a
         * jar with no source in the project, so stopping in it shows a file
         * the editor has nothing for.
         */
        val STEP_INTO_EXCLUDES = listOf(
            "java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*",
            "libcore.*", "dalvik.*", "android.*", "com.android.*",
            "androidx.*", "kotlin.*", "kotlinx.*",
        )
    }
}
