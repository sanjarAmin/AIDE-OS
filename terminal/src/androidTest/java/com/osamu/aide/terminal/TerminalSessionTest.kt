package com.osamu.aide.terminal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The session: bytes in, bytes out, and a lifetime.
 *
 * `PtyOnDeviceTest` covers what the platform allows. This covers what this
 * module adds on top -- the output pump, the state machine, and the difference
 * between stopping the reader and stopping the shell.
 *
 * Everything is asserted against a marker the test chose, because reading a
 * terminal is not reading a file: it echoes what was typed, output arrives in
 * whatever chunks the kernel felt like, and there is no EOF until the shell
 * exits. `tools/pty/FINDINGS.md` section 7.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSessionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var session: TerminalSession
    private lateinit var collected: StringBuilder
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        scope = CoroutineScope(Job() + Dispatchers.Default)
        collected = StringBuilder()
        session = TerminalSession(DefaultDispatcherProvider(), workingDirectory = cacheDir)
    }

    @After
    fun tearDown() {
        session.close()
        scope.cancel()
    }

    /** Starts the shell and collects everything it writes. */
    private fun start(columns: Int = 80, rows: Int = 24) {
        scope.launch {
            session.output.collect { chunk -> synchronized(collected) { collected.append(String(chunk)) } }
        }
        val started = session.start(scope, columns, rows)
        assertTrue("the shell did not start: $started", started is AppResult.Success)
    }

    /**
     * Types [command] so that its marker cannot appear in the terminal's echo.
     *
     * **The terminal echoes what was typed**, so waiting for a marker that is
     * literally in the command matches the echo and returns before the shell
     * has run anything. Splitting the marker with shell quoting -- `AB'-'CD`
     * echoes as `AB'-'CD` and prints as `AB-CD` -- makes the two
     * distinguishable, so the wait is for the answer.
     *
     * This was not a hypothetical: the resize test passed its waits on echoes
     * and then asserted against output that had not arrived.
     */
    private suspend fun runAndAwait(command: String, marker: String, timeoutMs: Long = 15_000) {
        val split = marker.length / 2
        val quoted = marker.take(split) + "'" + marker.substring(split) + "'"
        session.write("$command; echo $quoted\n")
        awaitOutput(marker, timeoutMs)
    }

    private suspend fun awaitOutput(marker: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val seen = synchronized(collected) { collected.toString() }
            if (marker in seen) return
            // A real wait: under runTest `delay` is virtual, and the shell and
            // the pump are both on real dispatchers.
            withContext(Dispatchers.Default) { delay(25) }
        }
        throw AssertionError(
            "never saw '$marker'. Terminal said:\n${synchronized(collected) { collected.toString() }}",
        )
    }

    @Test
    fun a_shell_starts_and_its_output_arrives_as_a_flow() = runTest(timeout = 2.minutes) {
        start()
        assertEquals(TerminalState.Running, session.state.value)

        runAndAwait("true", "AIDE-OS-SESSION-WORKS")
    }

    /**
     * The size reaches the shell, which is what `SIGWINCH` is for.
     *
     * A terminal that never reports one leaves every full-screen program
     * believing it has 80x24 forever.
     */
    @Test
    fun a_resize_reaches_the_shell() = runTest(timeout = 2.minutes) {
        start()
        runAndAwait("true", "READY-FOR-RESIZE")

        assertTrue(session.resize(columns = 132, rows = 43) is AppResult.Success)

        runAndAwait("stty size", "SIZE-REPORTED")
        val seen = synchronized(collected) { collected.toString() }
        assertTrue("the shell does not see the size we set:\n$seen", "43 132" in seen)
    }

    /**
     * Interrupt reaches the **foreground group**, not the shell.
     *
     * Asserted by control coming back, not by a trailing command running: a
     * shell abandons the rest of a command list when its foreground job is
     * interrupted, which is correct and makes `sleep 30; echo BACK` the wrong
     * test. `tools/pty/FINDINGS.md` section 7.
     */
    @Test
    fun an_interrupt_returns_control_to_the_shell() = runTest(timeout = 2.minutes) {
        start()
        runAndAwait("true", "READY-TO-INTERRUPT")

        session.write("sleep 30\n")
        withContext(Dispatchers.Default) { delay(1_000) }

        assertTrue("interrupt failed", session.interrupt() is AppResult.Success)

        runAndAwait("true", "BACK-AFTER-INTERRUPT")
    }

    /** The exit status is the shell's own, and reaching [TerminalState.Exited] says so. */
    @Test
    fun the_exit_status_is_reported() = runTest(timeout = 2.minutes) {
        start()
        runAndAwait("true", "READY-TO-EXIT")

        session.write("exit 3\n")

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && session.state.value !is TerminalState.Exited) {
            withContext(Dispatchers.Default) { delay(25) }
        }
        assertEquals(TerminalState.Exited(3), session.state.value)
    }

    /**
     * A second [TerminalSession.start] is refused rather than leaking the first.
     *
     * Each session owns one `Pty` with one file descriptor and one child. A
     * second start would overwrite the field and leave the first shell running
     * with nothing reading it.
     */
    @Test
    fun starting_twice_is_refused() = runTest(timeout = 2.minutes) {
        start()
        val again = session.start(scope)
        assertTrue("a second start was allowed", again is AppResult.Failure)
        assertEquals(TerminalState.Running, session.state.value)
    }

    /**
     * Cancelling the reader's scope does **not** kill the shell.
     *
     * The two are separate on purpose: a screen going away is not a reason to
     * kill a build running in the terminal. `close` is what stops the shell,
     * and the test proves the distinction by cancelling and then finding the
     * process still alive.
     */
    @Test
    fun cancelling_the_reader_leaves_the_shell_running() = runTest(timeout = 2.minutes) {
        start()
        runAndAwait("true", "READY-BEFORE-CANCEL")

        scope.cancel()
        withContext(Dispatchers.Default) { delay(500) }

        // Still running: nothing reaped it, so the state has not moved on.
        assertEquals(TerminalState.Running, session.state.value)

        session.close()
        Log.i("Terminal", "closed after cancelling the reader")
    }
}
