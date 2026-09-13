package com.osamu.aide.debugger

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * A breakpoint in a class that has not loaded yet.
 *
 * Most classes in a just-started app are in that state, and it is the case
 * [DebugSession.breakpointAt] cannot handle: `ClassesBySignature` answers
 * nothing for them. The debuggee's `LateLoaded` is loaded by reflection five
 * seconds after launch, so this restarts the debuggee, attaches inside that
 * window, and proves the class is absent **before** asking to be told when it
 * arrives -- without that check the test would pass on the ordinary path and
 * prove nothing about the deferred one.
 *
 * Restarting is why this is its own class rather than part of
 * [DebugSessionTest]: that one attaches in `@Before` to whatever debuggee is
 * running, and this one kills it.
 */
@RunWith(AndroidJUnit4::class)
class DeferredBreakpointTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var session: DebugSession? = null

    /**
     * Runs a command as `shell` and waits for it to finish.
     *
     * The descriptor must be read to its end: `executeShellCommand` returns as
     * soon as the command starts, and a `force-stop` still in flight when
     * `am start` runs leaves the old process alive and the old port bound.
     */
    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun debuggeeInstalled(): Boolean = shell("pm path $PACKAGE").contains("package:")

    private fun restartDebuggee(port: Int) {
        shell("am force-stop $PACKAGE")
        shell("am start -W -n $PACKAGE/.DebuggeeActivity --ei port $port")
    }

    /**
     * Connects as soon as the agent is listening.
     *
     * Retried because the provider-less debuggee opens its port in `onCreate`,
     * a moment after `am start` returns -- and every retry here is time taken
     * out of the five-second window the whole test depends on.
     */
    private suspend fun attachWhenListening(port: Int): DebugSession {
        val deadline = System.currentTimeMillis() + ATTACH_BUDGET_MS
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return attachDebugger(port = port, connectTimeoutMs = 500)
            } catch (t: Throwable) {
                last = t
                delay(100)
            }
        }
        throw AssertionError("the debuggee never listened on $port", last)
    }

    /**
     * Leaves a debuggee running on the default port, as [DebugSessionTest]
     * expects to find one -- this test having killed the one it found.
     */
    @After
    fun tearDown() {
        session?.let { live ->
            runBlocking { runCatching { live.resume() } }
            live.close()
        }
        if (debuggeeInstalled()) restartDebuggee(DEFAULT_PORT)
    }

    @Test
    fun a_breakpoint_in_a_class_that_has_not_loaded_fires_when_it_does() = runBlocking {
        assumeTrue(
            "the debuggee is not installed: ./gradlew :spike:jdwpdebuggee:installDebug",
            debuggeeInstalled(),
        )
        // A port of its own, so nothing is racing a socket the previous
        // process left in TIME_WAIT on the default one.
        restartDebuggee(PORT)
        val debugger = attachWhenListening(PORT).also { session = it }

        assertTrue(
            "$LATE_CLASS was already loaded when the debugger attached, so this " +
                "would test the ordinary path and not the deferred one",
            debugger.classesBySignature(classSignature(LATE_CLASS)).isEmpty(),
        )

        val prepareRequest = debugger.requestClassPrepare(LATE_CLASS)

        val prepared = withTimeout(LATE_LOAD_TIMEOUT_MS) {
            debugger.events.mapNotNull { set ->
                set.events.filterIsInstance<JdwpEvent.ClassPrepare>()
                    .firstOrNull { it.requestId == prepareRequest }
            }.first()
        }
        assertEquals(classSignature(LATE_CLASS), prepared.signature)

        // The loading thread is held here -- EVENT_THREAD -- so nothing in the
        // class has run, and the breakpoint below is certain to be in place
        // before the line it is on executes for the first time.
        val run = debugger.methods(prepared.typeId).single { it.name == "run" }
        val line = debugger.lineTable(prepared.typeId, run.id).minBy { it.codeIndex }.lineNumber
        val breakpoint = debugger.breakpointInType(prepared.typeTag, prepared.typeId, line)
        assertNotNull("no code at line $line of $LATE_CLASS.run", breakpoint)

        debugger.clearClassPrepare(prepareRequest)
        debugger.resumeThread(prepared.threadId)

        val hit = withTimeout(LATE_LOAD_TIMEOUT_MS) {
            debugger.events.mapNotNull { set ->
                set.events.filterIsInstance<JdwpEvent.Breakpoint>()
                    .firstOrNull { it.requestId == breakpoint!!.requestId }
            }.first()
        }
        assertEquals("stopped outside the late class", prepared.typeId, hit.location.classId)
        assertEquals("stopped outside run()", run.id, hit.location.methodId)
        assertEquals(
            "the late class ran on a different thread from the one that loaded it",
            "debuggee-late",
            debugger.threadName(hit.threadId),
        )
        android.util.Log.w("DebuggerTest", "deferred breakpoint hit in $LATE_CLASS.run at line $line")

        debugger.clearBreakpoint(breakpoint!!.requestId)
        debugger.resumeThread(hit.threadId)
    }

    private companion object {
        const val PACKAGE = "com.osamu.aide.spike.jdwpdebuggee"
        const val LATE_CLASS = "com.osamu.aide.spike.jdwpdebuggee.LateLoaded"
        const val PORT = 8701
        const val DEFAULT_PORT = 8700
        const val ATTACH_BUDGET_MS = 3_000L
        const val LATE_LOAD_TIMEOUT_MS = 15_000L
    }
}
