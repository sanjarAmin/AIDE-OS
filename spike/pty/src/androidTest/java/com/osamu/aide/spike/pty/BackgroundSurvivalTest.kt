package com.osamu.aide.spike.pty

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Does a shell survive the app being backgrounded?**
 *
 * The one question spike R7 left open, and the one that decides whether a
 * terminal can be a tab you leave open rather than something that has to be
 * restarted every time the user checks a message.
 *
 * Asked with a real Activity in a real task, because the instrumentation
 * process is never *cached*: a shell forked from it is never subject to the
 * freezer or to the process-group kills Android applies to a backgrounded app.
 *
 * The shell appends a line a second to a file. The measurement is whether that
 * file keeps growing while the home screen is showing.
 *
 * **Reported as well as asserted.** The assertion is deliberately weak -- that
 * the shell was still alive at all -- because the exact behaviour is an OEM and
 * version policy question, and a hard threshold here would measure this
 * emulator rather than Android. The number in logcat is the finding.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundSurvivalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    // The *target* context: the shell writes into the app under test's cache
    // dir, not the instrumentation process's.
    private val cacheDir: File get() = instrumentation.targetContext.cacheDir

    @Test
    fun a_shell_keeps_running_while_the_app_is_in_the_background() {
        val heartbeat = File(cacheDir, ShellHostActivity.HEARTBEAT)

        instrumentation.context.startActivity(
            Intent(instrumentation.targetContext, ShellHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        val beforeBackground = waitForLines(heartbeat, atLeast = 2, timeoutMs = 20_000)
        Log.i(TAG, "shell produced $beforeBackground lines while in the foreground")

        device.pressHome()
        // Long enough for the app to be cached and, on API 34, for the freezer
        // to have had its chance. Longer than any UI transition.
        Thread.sleep(BACKGROUND_MS)

        val afterBackground = heartbeat.readLinesOrEmpty().size
        val produced = afterBackground - beforeBackground
        Log.i(
            TAG,
            "after ${BACKGROUND_MS / 1000}s in the background: $afterBackground lines " +
                "(+$produced while away)",
        )

        // Alive at all is the claim. Whether it was throttled is the number
        // above, and it belongs in FINDINGS rather than in an assertion that
        // would pin this emulator's scheduling policy.
        assertTrue(
            "the shell stopped entirely while the app was in the background " +
                "($beforeBackground lines before, $afterBackground after)",
            produced > 0,
        )
    }

    private fun waitForLines(file: File, atLeast: Int, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val lines = file.readLinesOrEmpty().size
            if (lines >= atLeast) return lines
            Thread.sleep(200)
        }
        throw AssertionError(
            "the shell never wrote $atLeast lines in the foreground, so there is " +
                "nothing to compare against",
        )
    }

    private fun File.readLinesOrEmpty(): List<String> =
        runCatching { readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "PtySpike"
        const val BACKGROUND_MS = 45_000L
    }
}
