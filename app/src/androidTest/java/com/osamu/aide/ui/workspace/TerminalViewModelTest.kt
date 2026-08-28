package com.osamu.aide.ui.workspace

import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.terminal.TerminalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The terminal panel's view model, over the vendored emulator.
 *
 * `EmulatedTerminalTest` covers the emulator itself. This covers what the panel
 * depends on: that typing goes through character by character, that the screen
 * it publishes is the emulator's, and that a dead shell can be replaced.
 *
 * Markers are split with shell quoting throughout, for the reason
 * `tools/pty/FINDINGS.md` section 10 records: a terminal echoes what it is
 * sent, so a marker literally present in the command matches the echo and the
 * test passes before the shell has run anything.
 */
class TerminalViewModelTest {

    private lateinit var directory: File
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "terminal-vm-test").apply {
            deleteRecursively()
            mkdirs()
        }
        viewModel = TerminalViewModel(DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        viewModel.shutdown()
        directory.deleteRecursively()
    }

    private val screen: String get() = viewModel.state.value.output

    private suspend fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(25) }
        }
        throw AssertionError("timed out waiting for $what\nscreen:\n$screen")
    }

    private suspend fun ready() {
        viewModel.open(directory)
        await("the shell") { viewModel.state.value.isRunning }
    }

    private suspend fun run(command: String, marker: String) {
        val split = marker.length / 2
        val quoted = marker.take(split) + "'" + marker.substring(split) + "'"
        viewModel.type("$command; echo $quoted\n")
        await(marker) { marker in screen }
    }

    @Test
    fun a_command_runs_and_its_output_reaches_the_screen() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "PANEL-OVER-EMULATOR")
    }

    /** The shell really is in the project's folder, which is the point of it. */
    @Test
    fun the_shell_starts_in_the_project_directory() = runTest(timeout = 3.minutes) {
        ready()
        File(directory, "marker-file.txt").writeText("here\n")

        run("ls", "LISTED")
        assertTrue("the shell is not in the project directory:\n$screen", "marker-file.txt" in screen)
    }

    /**
     * Typing goes through as it happens, not on Enter.
     *
     * The characters appear on screen from the terminal's own echo *before*
     * any newline is sent, which is what an interactive program depends on and
     * what the previous line-at-a-time panel could not do.
     */
    @Test
    fun characters_reach_the_shell_before_any_newline() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "READY-TO-TYPE")

        viewModel.type("echo PARTI")
        await("the echo of a partial line") { "PARTI" in screen }
        assertFalse("something ran without a newline", "PARTIAL-DONE" in screen)

        viewModel.type("AL-DONE\n")
        await("the completed command") { screen.count { it == 'P' } >= 2 }
    }

    /**
     * Ctrl-C as a typed character, which is the path a user takes.
     *
     * Different from [TerminalViewModel.interrupt], which signals the process
     * group directly. Both should work and this is the one behind the keyboard.
     */
    @Test
    fun control_c_typed_as_a_character_interrupts() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "READY-TO-INTERRUPT")

        viewModel.type("sleep 30\n")
        withContext(Dispatchers.Default) { delay(1_000) }

        viewModel.typeChar('c', controlDown = true)
        run("true", "BACK-AFTER-CONTROL-C")
    }

    /**
     * An arrow key reaches the shell's line editor.
     *
     * Asserted by its effect: type `xy`, press Left, type `Z`, and the command
     * that runs is `echo xZy`. That only happens if a real cursor sequence
     * arrived, which is what the vendored `KeyHandler` is for.
     */
    @Test
    fun an_arrow_key_edits_the_command_line() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "READY-FOR-ARROWS")

        viewModel.type("echo xy")
        viewModel.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
        viewModel.type("Z\n")

        await("the edited command's output") { "xZy" in screen }
    }

    /** A shell that exits is reported, and can be replaced. */
    @Test
    fun an_exited_shell_can_be_restarted() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "BEFORE-THE-EXIT")

        viewModel.type("exit 3\n")
        await("the exit") { viewModel.state.value.state is TerminalState.Exited }
        assertEquals(TerminalState.Exited(3), viewModel.state.value.state)

        viewModel.restart()
        await("the new shell") { viewModel.state.value.isRunning }
        run("true", "AFTER-THE-RESTART")
    }

    /** Opening twice does not start a second shell over the first. */
    @Test
    fun opening_twice_keeps_one_shell() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "FIRST-SHELL")

        viewModel.open(directory)
        withContext(Dispatchers.Default) { delay(500) }

        assertTrue("the shell was replaced", viewModel.state.value.isRunning)
        run("true", "SAME-SHELL")
    }

    /**
     * A resize reaches the shell, and the published screen agrees.
     *
     * The view is the only thing that knows how many monospace cells fit, so it
     * drives this; a terminal whose emulator and shell disagree about the width
     * wraps in the wrong place.
     */
    @Test
    fun a_resize_reaches_the_shell_and_the_screen() = runTest(timeout = 3.minutes) {
        ready()
        run("true", "READY-TO-RESIZE")

        viewModel.resize(columns = 100, rows = 30)
        await("the new size") { viewModel.state.value.screen.columns == 100 }
        assertEquals(30, viewModel.state.value.screen.rows)

        run("stty size", "SIZE-REPORTED")
        assertTrue("the shell does not see the new size:\n$screen", "30 100" in screen)
    }
}
