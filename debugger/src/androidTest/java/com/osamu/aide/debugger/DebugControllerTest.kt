package com.osamu.aide.debugger

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The controller the Debug panel drives, against a running app in another
 * package.
 *
 * Where [DebugSessionTest] proves the protocol, this proves the thing a person
 * uses: a breakpoint named by **source file and line** -- the only identity an
 * editor has -- stops the app, and the stopped state carries what the panel
 * shows. A key that the controller resolves differently from the editor would
 * install nowhere and never fire, and every assertion here would time out
 * rather than fail with a message; so each wait says what it was waiting for.
 */
@RunWith(AndroidJUnit4::class)
class DebugControllerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: DebugController? = null

    @Before
    fun setUp() {
        Debuggee.bringForward(PORT)
        assumeTrue(
            "no debuggee on 127.0.0.1:$PORT, and it could not be started: is :spike:jdwpdebuggee installed?",
            canConnect(PORT),
        )
    }

    @After
    fun tearDown() {
        controller?.let { live ->
            live.detach()
            runBlocking { withTimeout(5_000) { live.state.first { it is DebugState.Ended || it is DebugState.Idle } } }
        }
        scope.cancel()
    }

    /**
     * Whether a debuggee is listening, allowing for the agent between sessions.
     *
     * **libjdwp closes its listening socket while a debugger is attached and
     * opens a new one after it leaves**, and the gap is up to ~50 ms, measured
     * over `adb forward`. A test that checks straight after the previous one
     * detached lands in it, is refused, and skips -- four skips in a row the
     * first time this ran, which looked like the debuggee had died. It had not.
     */
    private fun canConnect(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                true
            }.getOrDefault(false)
            if (connected) return true
            Thread.sleep(100)
        }
        return false
    }

    /**
     * The first line of the debuggee's `step`, read out of the running app.
     *
     * Asked of the VM through a short-lived plain session rather than written
     * here, so that editing the debuggee cannot quietly move this test onto a
     * blank line. The agent listens again once that session closes.
     */
    private fun stepLine(): Int = runBlocking {
        val probe = attachDebugger(port = PORT)
        try {
            val type = probe.classesBySignature(classSignature(DEBUGGEE_CLASS)).single()
            val step = probe.methods(type.typeId).single { it.name == "step" }
            probe.lineTable(type.typeId, step.id).minBy { it.codeIndex }.lineNumber
        } finally {
            probe.close()
            delay(300)
        }
    }

    private suspend inline fun <reified T : DebugState> DebugController.await(
        what: String,
        timeoutMs: Long = 15_000,
        crossinline predicate: (T) -> Boolean = { true },
    ): T = try {
        withTimeout(timeoutMs) { state.first { it is T && predicate(it) } as T }
    } catch (e: Exception) {
        throw AssertionError("timed out waiting for $what; state is ${state.value}", e)
    }

    @Test
    fun a_breakpoint_by_source_line_stops_the_app_and_shows_its_frame(): Unit = runBlocking {
        val line = SourceLine(ACTIVITY_KEY, stepLine())
        val debugger = DebugController(scope).also { controller = it }

        assertTrue("attach failed: ${debugger.state.value}", debugger.attach(PORT, setOf(line)))
        val stopped = debugger.await<DebugState.Stopped>("the breakpoint at $line")

        val top = stopped.frame
        assertNotNull("stopped with no frames", top)
        assertEquals("the top frame is not the breakpoint's line", line, top!!.source)
        assertEquals("DebuggeeActivity.step", top.title)
        assertEquals("debuggee-work", stopped.threadName)

        val counter = stopped.variables.singleOrNull { it.name == "counter" }
        assertNotNull("no `counter` among ${stopped.variables.map { it.name }}", counter)
        assertEquals("int", counter!!.type)
        assertTrue("counter is not a number: ${counter.value}", counter.value.toIntOrNull() != null)

        // `this` first, and openable: the field it holds must agree with the
        // local, for the reason DebugSessionTest gives.
        val self = stopped.variables.first()
        assertEquals("this", self.name)
        assertNotNull("`this` cannot be opened", self.objectId)
        debugger.toggleExpanded(self.objectId!!)
        val opened = debugger.await<DebugState.Stopped>("the fields of `this`") { self.objectId in it.expanded }
        val ticks = opened.expanded.getValue(self.objectId).singleOrNull { it.name == "ticks" }
        assertEquals("the field disagrees with the local", counter.value, ticks?.value)
        val label = opened.expanded.getValue(self.objectId).singleOrNull { it.name == "label" }
        assertEquals("\"jdwp-debuggee\"", label?.value)
    }

    /**
     * Resume lets the thread go -- and a breakpoint that is still set stops it
     * again, while one that has been removed does not.
     */
    @Test
    fun resume_runs_until_the_next_hit_and_a_removed_breakpoint_stays_quiet(): Unit = runBlocking {
        val line = SourceLine(ACTIVITY_KEY, stepLine())
        val debugger = DebugController(scope).also { controller = it }
        debugger.attach(PORT, setOf(line))

        val first = debugger.await<DebugState.Stopped>("the first hit")
        val firstCounter = first.variables.single { it.name == "counter" }.value.toInt()

        debugger.resume()
        val second = debugger.await<DebugState.Stopped>("the second hit") {
            it.variables.singleOrNull { v -> v.name == "counter" }?.value?.toIntOrNull() != firstCounter
        }
        val secondCounter = second.variables.single { it.name == "counter" }.value.toInt()
        assertTrue("resume did not let the loop advance: $firstCounter -> $secondCounter", secondCounter > firstCounter)

        debugger.setBreakpoints(emptySet())
        delay(300)
        debugger.resume()
        debugger.await<DebugState.Running>("running after resume")
        delay(1_000)
        assertTrue(
            "a removed breakpoint stopped the app: ${debugger.state.value}",
            debugger.state.value is DebugState.Running,
        )
    }

    @Test
    fun step_over_arrives_on_a_later_line_of_the_same_file(): Unit = runBlocking {
        val line = SourceLine(ACTIVITY_KEY, stepLine())
        val debugger = DebugController(scope).also { controller = it }
        debugger.attach(PORT, setOf(line))
        debugger.await<DebugState.Stopped>("the breakpoint")

        // Removed first, so the loop coming round cannot beat the step.
        debugger.setBreakpoints(emptySet())
        delay(300)
        debugger.stepOver()
        val stepped = debugger.await<DebugState.Stopped>("the step to finish") {
            it.frame?.source != null && it.frame?.source != line
        }
        assertEquals(ACTIVITY_KEY, stepped.frame!!.source!!.key)
        assertTrue(
            "the step did not move forward: ${stepped.frame!!.source}",
            stepped.frame!!.source!!.line > line.line,
        )
    }

    /**
     * A step that lands on a breakpoint is one stop, not two.
     *
     * The VM reports it as a single event set carrying a SingleStep *and* a
     * Breakpoint for the same thread. Taken as two stops, the second queued as
     * "1 more waiting" -- and Resume then showed that queued stop, for a thread
     * it had just resumed. Found driving a Kotlin app, stepping through a
     * lambda that held a breakpoint.
     */
    @Test
    fun a_step_onto_a_breakpoint_is_a_single_stop(): Unit = runBlocking {
        val line = SourceLine(ACTIVITY_KEY, stepLine())
        val debugger = DebugController(scope).also { controller = it }
        debugger.attach(PORT, setOf(line))
        debugger.await<DebugState.Stopped>("the breakpoint")

        // Where a step from here arrives, learned with nothing else set so the
        // loop cannot interfere.
        debugger.setBreakpoints(emptySet())
        delay(300)
        debugger.stepOver()
        val landing = debugger.await<DebugState.Stopped>("the first step") {
            it.frame?.source != null && it.frame?.source != line
        }.frame!!.source!!

        // Both lines now: come round to the first, then step onto the second.
        debugger.setBreakpoints(setOf(line, landing))
        delay(300)
        debugger.resume()
        debugger.await<DebugState.Stopped>("the loop to come round to $line") { it.frame?.source == line }
        debugger.stepOver()
        val onBoth = debugger.await<DebugState.Stopped>("the step onto the breakpoint at $landing") {
            it.frame?.source == landing
        }
        delay(500)
        assertEquals(
            "one suspension was counted as more than one stop: ${debugger.state.value}",
            0,
            (debugger.state.value as DebugState.Stopped).waiting,
        )

        // And Resume really runs: the next stop is the loop coming round to the
        // first line, not a second showing of the one just left.
        debugger.resume()
        val next = debugger.await<DebugState.Stopped>("the next hit after resuming") {
            it.frame?.source != landing || it.variables != onBoth.variables
        }
        assertEquals("Resume showed a stale stop instead of running", line, next.frame?.source)
    }

    /** Detach must leave the app running, not frozen on the thread it stopped. */
    @Test
    fun detaching_while_stopped_lets_the_app_run_on(): Unit = runBlocking {
        val line = SourceLine(ACTIVITY_KEY, stepLine())
        val debugger = DebugController(scope).also { controller = it }
        debugger.attach(PORT, setOf(line))
        debugger.await<DebugState.Stopped>("the breakpoint")

        debugger.detach()
        debugger.await<DebugState.Ended>("the session to end")
        controller = null
        delay(500)

        // A fresh session finds the loop moving: its breakpoint fires, which a
        // thread still suspended from the old session could never do.
        val again = DebugController(scope).also { controller = it }
        again.attach(PORT, setOf(line))
        again.await<DebugState.Stopped>("a hit after re-attaching, proving the app was left running")
    }

    /**
     * A breakpoint in a file none of whose classes exist yet.
     *
     * Restarts the debuggee, which loads `LateLoaded` by reflection five
     * seconds in; see [DeferredBreakpointTest] for why by reflection. The line
     * is `run`'s first, written here because a class that is not loaded has no
     * line table to ask -- the file itself says `val greeting` is on it.
     */
    @Test
    fun a_breakpoint_in_a_file_that_has_not_loaded_fires_when_it_does(): Unit = runBlocking {
        shell("am force-stop $PACKAGE")
        shell("am start -W -n $PACKAGE/.DebuggeeActivity --ei port $LATE_PORT")
        try {
            val line = SourceLine(LATE_KEY, LATE_RUN_LINE)
            val debugger = DebugController(scope).also { controller = it }
            assertTrue("attach failed: ${debugger.state.value}", debugger.attach(LATE_PORT, setOf(line)))
            val stopped = debugger.await<DebugState.Stopped>("the deferred breakpoint at $line")
            assertEquals(line, stopped.frame?.source)
            assertEquals("LateLoaded.run", stopped.frame?.title)
        } finally {
            controller?.detach()
            controller?.let { live -> withTimeout(5_000) { live.state.first { it is DebugState.Ended } } }
            controller = null
            shell("am force-stop $PACKAGE")
            shell("am start -W -n $PACKAGE/.DebuggeeActivity --ei port $PORT")
        }
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        const val PACKAGE = "com.osamu.aide.spike.jdwpdebuggee"
        const val DEBUGGEE_CLASS = "$PACKAGE.DebuggeeActivity"
        const val ACTIVITY_KEY = "com/osamu/aide/spike/jdwpdebuggee/DebuggeeActivity.kt"
        const val LATE_KEY = "com/osamu/aide/spike/jdwpdebuggee/LateLoaded.kt"
        const val LATE_RUN_LINE = 22
        const val PORT = 8700
        const val LATE_PORT = 8702
    }
}
