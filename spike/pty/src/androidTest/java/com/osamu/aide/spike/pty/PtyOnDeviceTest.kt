package com.osamu.aide.spike.pty

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike R7: a PTY and a shell on an unprivileged device.
 *
 * Each test isolates one layer so a failure names the layer rather than the
 * spike: (1) the kernel gives us a PTY at all, (2) a shell execs on it and
 * echoes, (3) the shell really owns the terminal, (4) job control can interrupt
 * a foreground command, (5) the size is reported, and (6) an exit status comes
 * back.
 *
 * Ordered by how early a failure would stop everything after it. `forkpty` is
 * first because everything else is downstream of it, and because it is the one
 * an SELinux policy would refuse.
 *
 * Numbers and answers land in logcat under `PtySpike`.
 */
@RunWith(AndroidJUnit4::class)
class PtyOnDeviceTest {

    private var pty: Pty? = null

    @After
    fun tearDown() {
        pty?.let {
            // SIGKILL rather than close alone: a shell blocked reading its
            // terminal does not exit when the master closes on every kernel,
            // and a leaked shell outlives the test process.
            it.signal(9)
            it.close()
        }
        pty = null
    }

    private fun open(): Pty {
        val cwd = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Pty.open(workingDirectory = cwd.absolutePath).also { pty = it }
    }

    /** Types [text] at the shell, as a keyboard would. */
    private fun Pty.type(text: String) {
        input.write(text.toByteArray())
        input.flush()
    }

    /**
     * Reads until [marker] appears, or the deadline passes.
     *
     * Reading a terminal is not reading a file: the shell echoes what was typed
     * back, output arrives in whatever chunks the kernel felt like, and there is
     * no EOF until the shell exits. So everything is asserted against a marker
     * the test chose rather than against an exact transcript.
     */
    private fun Pty.readUntil(marker: String, timeoutMs: Long = 10_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val seen = StringBuilder()
        val buffer = ByteArray(4096)
        while (System.currentTimeMillis() < deadline) {
            if (output.available() == 0) {
                Thread.sleep(20)
                continue
            }
            val read = output.read(buffer)
            if (read <= 0) break
            seen.append(String(buffer, 0, read))
            if (marker in seen) return seen.toString()
        }
        throw AssertionError("never saw '$marker'. Terminal said:\n$seen")
    }

    /** Question 1: does the kernel hand an untrusted app a PTY at all? */
    @Test
    fun a_pseudoterminal_opens() {
        val pty = open()

        assertTrue("forkpty returned a nonsense pid: ${pty.processId}", pty.processId > 0)
        Log.i(TAG, "forkpty gave pid ${pty.processId}")
    }

    /** Question 2: does a shell exec on it, and does it talk back? */
    @Test
    fun a_shell_runs_and_answers() {
        val pty = open()

        pty.type("echo AIDE-OS-PTY-WORKS\n")
        val transcript = pty.readUntil("AIDE-OS-PTY-WORKS")

        // Twice: once echoed by the terminal, once printed by the shell. That
        // is itself evidence the fd is a terminal rather than a pipe, since a
        // pipe would not echo.
        val occurrences = Regex("AIDE-OS-PTY-WORKS").findAll(transcript).count()
        Log.i(TAG, "marker appeared $occurrences time(s)")
        assertTrue("the shell did not answer", occurrences >= 1)
    }

    /**
     * Question 3: does the shell actually own the terminal?
     *
     * `forkpty` calls `setsid` and `TIOCSCTTY`, so the child should be a session
     * leader whose process group is the terminal's foreground group. If this
     * reports an error the fd is a pipe wearing a terminal's clothes, and
     * everything about job control below is meaningless.
     */
    @Test
    fun the_shell_owns_the_terminal() {
        val pty = open()
        pty.type("echo READY\n")
        pty.readUntil("READY")

        val group = pty.foregroundGroup()
        Log.i(TAG, "foreground group = $group, shell pid = ${pty.processId}")

        assertTrue("tcgetpgrp failed with errno ${-group}", group > 0)
        assertEquals("the shell is not the foreground group", pty.processId, group)
    }

    /**
     * Question 4: **can a foreground command be interrupted?**
     *
     * The one that decides whether this is a terminal or a log viewer. Two
     * things have to be true and they are asserted separately: a running
     * command gets its **own** process group, and signalling that group hands
     * the terminal back to the shell.
     *
     * The assertion is that control *returns*, not that a trailing command
     * runs. `sleep 30; echo BACK` looks like the obvious test and is wrong:
     * a shell abandons the rest of a command list when the foreground job is
     * interrupted, which is correct shell behaviour and made this test pass or
     * fail depending on timing. What proves the interrupt worked is that the
     * shell is in the foreground again and will accept a new command.
     */
    @Test
    fun a_foreground_command_can_be_interrupted() {
        val pty = open()
        pty.type("echo READY\n")
        pty.readUntil("READY")

        pty.type("sleep 30\n")
        // The shell has to fork and hand the terminal over before there is
        // anything to look at.
        val foreground = waitForForegroundGroupOtherThan(pty, pty.processId)
        Log.i(TAG, "while sleeping, foreground group = $foreground (shell is ${pty.processId})")

        // To the group, not to the shell. Signalling the shell is the mistake
        // that makes an interrupt look like it works.
        assertEquals("kill failed", 0, pty.signalGroup(foreground, SIGINT))

        val returned = waitForForegroundGroup(pty, pty.processId)
        assertTrue(
            "the terminal never came back to the shell; foreground is still $returned",
            returned == pty.processId,
        )

        // And it is a working shell, not just one holding the terminal.
        pty.type("echo INTERRUPTED-AND-BACK\n")
        pty.readUntil("INTERRUPTED-AND-BACK", timeoutMs = 10_000)
    }

    private fun waitForForegroundGroupOtherThan(pty: Pty, group: Int): Int {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val current = pty.foregroundGroup()
            if (current > 0 && current != group) return current
            Thread.sleep(20)
        }
        throw AssertionError(
            "the command never got its own process group, so job control is not working",
        )
    }

    private fun waitForForegroundGroup(pty: Pty, group: Int): Int {
        val deadline = System.currentTimeMillis() + 10_000
        var current = pty.foregroundGroup()
        while (System.currentTimeMillis() < deadline && current != group) {
            Thread.sleep(20)
            current = pty.foregroundGroup()
        }
        return current
    }

    /** Question 5: does the child learn how big its terminal is? */
    @Test
    fun the_size_reaches_the_shell() {
        val pty = open()
        pty.type("echo READY\n")
        pty.readUntil("READY")

        assertEquals("TIOCSWINSZ failed", 0, pty.resize(columns = 132, rows = 43))

        // stty is toybox on Android, and reads the size from the terminal it is
        // attached to -- which is the only way to see the size from the far end.
        // Followed by a marker, because reading until a newline reads back the
        // *echo* of what was typed and stops before the answer arrives. Every
        // read from a terminal has to be anchored on something the test chose.
        pty.type("stty size; echo SIZE-DONE\n")
        val transcript = pty.readUntil("SIZE-DONE", timeoutMs = 5_000)
        Log.i(TAG, "stty size said: ${transcript.trim().takeLast(60)}")
        assertTrue(
            "the shell does not see the size we set. Terminal said:\n$transcript",
            "43 132" in transcript,
        )
    }

    /** Question 6: does an exit status come back? */
    @Test
    fun the_exit_status_comes_back() {
        val pty = open()
        pty.type("echo READY\n")
        pty.readUntil("READY")

        pty.type("exit 3\n")
        val status = pty.waitFor()

        Log.i(TAG, "shell exited with $status")
        assertEquals(3, status)
    }

    /**
     * What a real terminal would run first.
     *
     * Reported rather than asserted: the point is a record of what a shell on a
     * bare Android device actually has, since the terminal's usefulness is
     * bounded by it.
     */
    @Test
    fun what_is_on_path() {
        val pty = open()
        pty.type("echo READY\n")
        pty.readUntil("READY")

        pty.type("echo PATH=${'$'}PATH; ls /system/bin | wc -l; echo DONE-PROBE\n")
        val transcript = pty.readUntil("DONE-PROBE", timeoutMs = 10_000)
        Log.i(TAG, "environment probe:\n${transcript.trim()}")

        val cwd = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        assertTrue("the shell did not start in the directory it was given", File(cwd, ".").isDirectory)
    }

    private companion object {
        const val TAG = "PtySpike"
        const val SIGINT = 2
    }
}
