package com.osamu.aide.terminal

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The vendored emulator, driven by a real shell.
 *
 * **These tests exist to prove the sequences are interpreted, not stripped.**
 * That distinction is the whole reason for vendoring: the previous
 * implementation deleted escape sequences and could not have passed
 * [a_carriage_return_overwrites_rather_than_wrapping] or
 * [clearing_the_screen_empties_it], because both depend on the emulator acting
 * on a sequence rather than discarding it.
 *
 * Markers are split with shell quoting throughout, for the reason
 * `tools/pty/FINDINGS.md` section 10 records: a terminal echoes what it is
 * sent, so a marker literally present in the command matches the echo and the
 * test passes before the shell has run anything.
 */
@RunWith(AndroidJUnit4::class)
class EmulatedTerminalTest {

    private lateinit var scope: CoroutineScope
    private lateinit var terminal: EmulatedTerminal
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "emulated-terminal-test",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
        scope = CoroutineScope(Job() + Dispatchers.Default)
        terminal = EmulatedTerminal(DefaultDispatcherProvider(), workingDirectory = directory)
    }

    @After
    fun tearDown() {
        terminal.close()
        scope.cancel()
        directory.deleteRecursively()
    }

    private fun start(columns: Int = 80, rows: Int = 24) {
        val started = terminal.start(scope, columns, rows)
        assertTrue("the shell did not start: $started", started is AppResult.Success)
    }

    private suspend fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        throw AssertionError("timed out waiting for $what\nscreen:\n${terminal.screen.value.text}")
    }

    /** Runs [command] and waits for a marker the echo cannot contain. */
    private suspend fun run(command: String, marker: String) {
        val split = marker.length / 2
        val quoted = marker.take(split) + "'" + marker.substring(split) + "'"
        terminal.write("$command; echo $quoted\n")
        await(marker) { marker in terminal.screen.value.text }
    }

    private val screen: String get() = terminal.screen.value.text

    @Test
    fun a_command_runs_and_reaches_the_screen() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }

        run("true", "EMULATOR-IS-WIRED")
    }

    /**
     * **A carriage return moves the cursor; it does not break the line.**
     *
     * `printf 'AAAA\rBB'` leaves `BBAA` on one line, because the CR returns to
     * column zero and the following characters overwrite in place. The previous
     * implementation turned CR into a newline, which was the only sane thing to
     * do without a cursor -- and would produce `AAAA` and `BB` on two lines
     * here. This is the assertion that separates the two designs.
     */
    @Test
    fun a_carriage_return_overwrites_rather_than_wrapping() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }
        run("true", "READY-FOR-CR")

        terminal.write("printf 'AAAA\\rBB\\n'\n")
        await("the overwritten line") { "BBAA" in screen }

        assertFalse("the line was broken instead of overwritten:\n$screen", "AAAA\nBB" in screen)
    }

    /**
     * Clearing the screen actually empties it.
     *
     * `clear` sends an erase sequence and a cursor home. A stripper would drop
     * both and leave every previous line on screen forever.
     */
    @Test
    fun clearing_the_screen_empties_it() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }
        run("true", "BEFORE-THE-CLEAR")

        assertTrue("the marker never appeared", "BEFORE-THE-CLEAR" in screen)

        terminal.write("clear\n")
        await("the screen to be cleared") { "BEFORE-THE-CLEAR" !in screen }
    }

    /**
     * Colour codes are consumed rather than printed.
     *
     * The characters land on screen; the SGR sequences around them do not. A
     * terminal that printed `[31m` literally would look broken in the first
     * second of use.
     */
    @Test
    fun colour_sequences_do_not_reach_the_screen() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }

        terminal.write("printf '\\033[31mRE''D\\033[0m\\n'\n")
        await("the coloured text") { "RED" in screen }

        // Asserted on the ESC character itself, not on "[31m". The shell
        // *echoes* the command, and what was typed contains the four literal
        // characters \033 -- so searching for the printable tail of the
        // sequence matches the echo and fails against a perfectly good
        // emulator, which is exactly what the first version of this did.
        // A screen read out of the emulator can never legitimately contain ESC.
        assertFalse(
            "an escape character reached the screen:\n$screen",
            screen.contains('\u001B'),
        )
    }

    /**
     * The shell is told the size, and the emulator agrees with it.
     *
     * Both halves matter: `stty size` proves the `TIOCSWINSZ` reached the
     * child, and the snapshot proves the emulator resized its own screen. A
     * terminal where those two disagree wraps lines in the wrong place.
     */
    @Test
    fun a_resize_reaches_both_the_emulator_and_the_shell() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }
        run("true", "READY-TO-RESIZE")

        terminal.resize(columns = 100, rows = 30)

        assertEquals(100, terminal.screen.value.columns)
        assertEquals(30, terminal.screen.value.rows)

        run("stty size", "SIZE-REPORTED")
        assertTrue("the shell does not see the new size:\n$screen", "30 100" in screen)
    }

    /**
     * **Ctrl-C typed at the keyboard**, not routed round the side.
     *
     * `sendChar('c', controlDown = true)` becomes byte 3, which the terminal
     * driver turns into `SIGINT` for the foreground group. That is a different
     * path from [EmulatedTerminal.interrupt], which signals the group directly,
     * and it is the one a user actually takes.
     */
    @Test
    fun control_c_typed_as_a_key_interrupts() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }
        run("true", "READY-TO-INTERRUPT")

        terminal.write("sleep 30\n")
        withContext(Dispatchers.Default) { delay(1_000) }

        terminal.sendChar('c', controlDown = true)

        run("true", "BACK-AFTER-CONTROL-C")
    }

    /**
     * An arrow key is mapped through the vendored `KeyHandler`, using the
     * emulator's current modes.
     *
     * Asserted by its effect on the shell's line editor: typing `xy`, pressing
     * Left, then typing `Z` gives `xZy` -- which only happens if the shell
     * received a real cursor sequence rather than a literal.
     */
    @Test
    fun an_arrow_key_reaches_the_shells_line_editor() = runTest(timeout = 3.minutes) {
        start()
        await("the shell") { terminal.state.value is TerminalState.Running }
        run("true", "READY-FOR-ARROWS")

        terminal.write("echo xy")
        terminal.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        terminal.write("Z\n")

        await("the edited command's output") { "xZy" in screen }
    }
}
