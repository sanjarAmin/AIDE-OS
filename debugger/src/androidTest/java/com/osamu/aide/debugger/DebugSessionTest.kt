package com.osamu.aide.debugger

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The debugger, against an app that is actually running.
 *
 * `:spike:jdwpdebuggee` is a **separate package** that attaches the platform's
 * `libjdwp.so` to itself, exactly as a debug build produced by AIDE-OS will.
 * Everything here therefore crosses an app boundary, which is the only version
 * of the question worth asking: a client that debugs its own process proves
 * nothing about an IDE debugging the app it built.
 *
 * **The assertion that matters is the value of a local variable.** Attaching,
 * finding a class and registering a breakpoint can all succeed against a VM
 * that never stops anything; reading `counter` out of a suspended frame and
 * getting the number the log is printing cannot.
 *
 * Skipped, not failed, without a debuggee -- and it has to be started by hand,
 * because a `connectedAndroidTest` run uninstalls what it installs:
 *
 *     ./gradlew :spike:jdwpdebuggee:installDebug
 *     adb shell am start -n com.osamu.aide.spike.jdwpdebuggee/.DebuggeeActivity
 */
@RunWith(AndroidJUnit4::class)
class DebugSessionTest {

    private var session: DebugSession? = null
    private var breakpoint: Breakpoint? = null
    private var stepRequest: Int? = null

    @Before
    fun setUp() {
        assumeTrue(
            "no debuggee listening on 127.0.0.1:$PORT -- see this class's comment",
            canConnect(),
        )
        session = runBlocking { attachDebugger(port = PORT) }
    }

    /**
     * Puts the debuggee back the way it was found.
     *
     * A breakpoint left registered stops the *next* run before it has attached,
     * and a thread left suspended stops the app for good -- both of which
     * present as the following test timing out for no reason.
     */
    @After
    fun tearDown() {
        val live = session ?: return
        runBlocking {
            runCatching { breakpoint?.let { live.clearBreakpoint(it.requestId) } }
            // A step request left registered stops the debuggee on every line
            // it executes, forever -- which the next run sees as an app doing
            // nothing rather than as a leak here.
            runCatching { stepRequest?.let { live.clearStep(it) } }
            runCatching { live.resume() }
        }
        live.close()
    }

    private fun canConnect(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", PORT), 2_000) }
        true
    }.getOrDefault(false)

    @Test
    fun attaching_reports_the_vm_on_the_other_end() {
        val version = session!!.version
        assertEquals("this is not ART: ${version.vmName}", "Dalvik", version.vmName)
        assertTrue("no JDWP version in $version", version.jdwpMajor >= 1)
    }

    @Test
    fun the_debuggees_own_class_is_found_by_signature() = runBlocking {
        val classes = session!!.classesBySignature(classSignature(DEBUGGEE_CLASS))
        assertEquals("expected exactly one loaded $DEBUGGEE_CLASS", 1, classes.size)
    }

    /**
     * A line with no code gets no breakpoint, and says so.
     *
     * The alternative -- attaching to the nearest line that does have code --
     * is how a debugger appears to ignore a breakpoint, and it is worse than a
     * refusal because the user has no way to tell.
     */
    @Test
    fun a_line_with_no_code_yields_no_breakpoint() = runBlocking {
        assertNull(session!!.breakpointAt(classSignature(DEBUGGEE_CLASS), lineNumber = 99_999))
    }

    /**
     * The whole loop: stop a running app on a line, read a local, let it go.
     *
     * The line is taken from the method's own line table rather than written
     * here as a number, so that editing `DebuggeeActivity.kt` cannot silently
     * turn this into a test of the wrong line -- while still going through
     * [DebugSession.breakpointAt], which is the line-number-shaped API the UI
     * will call.
     */
    @Test
    fun a_breakpoint_stops_the_app_and_a_local_can_be_read() = runBlocking {
        val debugger = session!!
        val signature = classSignature(DEBUGGEE_CLASS)

        val classRef = debugger.classesBySignature(signature).single()
        val step = debugger.methods(classRef.typeId).single { it.name == STEP_METHOD }
        val firstLine = debugger.lineTable(classRef.typeId, step.id)
            .minByOrNull { it.codeIndex }
        assertNotNull("$STEP_METHOD has no line table: was this built without debug info?", firstLine)

        val placed = debugger.breakpointAt(signature, firstLine!!.lineNumber)
        assertNotNull("no breakpoint at line ${firstLine.lineNumber}", placed)
        breakpoint = placed
        assertEquals(
            "the breakpoint landed in another method",
            STEP_METHOD,
            placed!!.method.name,
        )

        val hit = withTimeout(BREAKPOINT_TIMEOUT_MS) {
            debugger.events
                .first { set -> set.events.any { it is JdwpEvent.Breakpoint } }
                .events
                .filterIsInstance<JdwpEvent.Breakpoint>()
                .first()
        }
        assertEquals(
            "stopped somewhere other than the breakpoint",
            placed.requestId,
            hit.requestId,
        )

        // The thread is suspended now, which is what makes the next three
        // commands legal at all.
        val frames = debugger.frames(hit.threadId)
        assertTrue("a stopped thread with no frames", frames.isNotEmpty())
        assertEquals(
            "the innermost frame is not where we stopped",
            hit.location,
            frames.first().location,
        )

        val locals = debugger.variableTable(classRef.typeId, step.id)
            .filter { it.isLiveAt(hit.location.index) }
        val counter = locals.singleOrNull { it.name == COUNTER_VARIABLE }
        assertNotNull(
            "no live local named $COUNTER_VARIABLE at index ${hit.location.index}; " +
                "live locals were ${locals.map { it.name }}",
            counter,
        )

        val values = debugger.values(hit.threadId, frames.first().id, listOf(counter!!))
        val value = values[COUNTER_VARIABLE]
        assertTrue("$COUNTER_VARIABLE came back as $value", value is JdwpValue.Primitive)
        val number = (value as JdwpValue.Primitive).value
        assertTrue("$COUNTER_VARIABLE is not an Int: ${number::class.java}", number is Int)
        // The debuggee counts up from zero forever, so any run that got here
        // has a non-negative count -- and a zero would mean the value was read
        // at the wrong slot or the wrong width rather than that we were quick.
        assertTrue("implausible counter value $number", (number as Int) >= 0)
        android.util.Log.w(TAG, "stopped in $STEP_METHOD with $COUNTER_VARIABLE=$number")

        val name = debugger.threadName(hit.threadId)
        assertEquals("stopped the wrong thread", WORK_THREAD, name)

        debugger.resumeThread(hit.threadId)
    }

    /**
     * Stepping: stop, step one line, and arrive somewhere else in the same
     * method.
     *
     * **The assertion is that the code index moved and the method did not.**
     * A step that reports the location it started from is the failure this
     * catches -- and so is one that steps into `Log.d`, which is what
     * `StepDepth.INTO` would do and is the difference the depth argument
     * exists to make.
     */
    @Test
    fun stepping_over_a_line_moves_within_the_same_method() = runBlocking {
        val debugger = session!!
        val signature = classSignature(DEBUGGEE_CLASS)

        val classRef = debugger.classesBySignature(signature).single()
        val step = debugger.methods(classRef.typeId).single { it.name == STEP_METHOD }
        val firstLine = debugger.lineTable(classRef.typeId, step.id).minBy { it.codeIndex }

        val placed = debugger.breakpointAt(signature, firstLine.lineNumber)!!
        breakpoint = placed

        val stopped = debugger.awaitStop(placed.requestId)
        // Cleared before stepping: leaving it set means the loop comes round
        // and hits the breakpoint again, and the step event and the breakpoint
        // event race to be the next thing read.
        debugger.clearBreakpoint(placed.requestId)
        breakpoint = null

        val request = debugger.requestStep(stopped.threadId, depth = Jdwp.StepDepth.OVER)
        stepRequest = request
        debugger.resumeThread(stopped.threadId)

        val stepped = debugger.awaitStop(request)
        debugger.clearStep(request)
        stepRequest = null

        assertEquals(
            "the step left $STEP_METHOD",
            stopped.location.methodId,
            stepped.location.methodId,
        )
        assertTrue(
            "the step did not move: still at index ${stepped.location.index}",
            stepped.location.index > stopped.location.index,
        )
        android.util.Log.w(
            TAG,
            "stepped ${stopped.location.index} -> ${stepped.location.index}",
        )

        debugger.resumeThread(stepped.threadId)
    }

    /**
     * An object's fields can be read, and they agree with the frame.
     *
     * Stopped on the first line of `step(counter)`: the previous call set
     * `ticks = next`, and `next` is what this call received as `counter`. So
     * the field read out of `this` and the local read out of the frame have to
     * be **the same number** -- which a field read at the wrong offset, or from
     * the wrong object, cannot manage by accident.
     *
     * The string field is there for the other path: its value is an object id
     * and turning it into text is a further round trip.
     */
    @Test
    fun an_objects_fields_can_be_read_and_agree_with_its_locals() = runBlocking {
        val debugger = session!!
        val signature = classSignature(DEBUGGEE_CLASS)
        val classRef = debugger.classesBySignature(signature).single()
        val step = debugger.methods(classRef.typeId).single { it.name == STEP_METHOD }
        val firstLine = debugger.lineTable(classRef.typeId, step.id).minBy { it.codeIndex }

        val placed = debugger.breakpointAt(signature, firstLine.lineNumber)!!
        breakpoint = placed
        val stopped = debugger.awaitStop(placed.requestId)
        debugger.clearBreakpoint(placed.requestId)
        breakpoint = null

        val frame = debugger.frames(stopped.threadId).first()
        val live = debugger.variableTable(classRef.typeId, step.id)
            .filter { it.isLiveAt(stopped.location.index) }
        val locals = debugger.values(stopped.threadId, frame.id, live)

        val self = locals["this"] as? JdwpValue.Reference
        assertNotNull("no `this` in ${locals.keys}", self)
        val counter = (locals[COUNTER_VARIABLE] as JdwpValue.Primitive).value as Int

        val fields = debugger.fields(self!!.id).associateBy { it.name }
        val ticks = fields["ticks"]?.value as? JdwpValue.Primitive
        assertNotNull("no `ticks` among ${fields.keys}", ticks)
        assertEquals(
            "the field and the local disagree, so one was read from the wrong place",
            counter,
            ticks!!.value,
        )

        val label = fields["label"]?.value as? JdwpValue.Reference
        assertNotNull("no `label` among ${fields.keys}", label)
        assertEquals("jdwp-debuggee", debugger.stringValue(label!!.id))
        android.util.Log.w(TAG, "fields of this: ticks=${ticks.value} counter=$counter label ok")

        debugger.resumeThread(stopped.threadId)
    }

    /** Waits for the next stop belonging to [requestId]. */
    private suspend fun DebugSession.awaitStop(requestId: Int): JdwpEvent.Stopped =
        withTimeout(BREAKPOINT_TIMEOUT_MS) {
            events
                .mapNotNull { set ->
                    set.events
                        .filterIsInstance<JdwpEvent.Stopped>()
                        .firstOrNull { it.requestId == requestId }
                }
                .first()
        }

    private companion object {
        const val TAG = "DebuggerTest"
        const val PORT = 8700
        const val DEBUGGEE_CLASS = "com.osamu.aide.spike.jdwpdebuggee.DebuggeeActivity"
        const val STEP_METHOD = "step"
        const val COUNTER_VARIABLE = "counter"
        const val WORK_THREAD = "debuggee-work"
        const val BREAKPOINT_TIMEOUT_MS = 15_000L
    }
}
