package com.osamu.aide.toolchain.nativetools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Stopping a build stops the program it started.
 *
 * **Cancelling the coroutine used not to be enough.** The only suspending
 * calls in a run are the two stream drains, so cancelling unwound the caller
 * and left the process running: a Gradle build stopped from the toolbar went
 * on building, and the panel said it had stopped. Nothing observable inside
 * the app contradicted that, which is why this asserts on the *child's* own
 * behaviour -- a file it appends to while it lives -- rather than on anything
 * the runner returns.
 *
 * On device rather than as a unit test because the thing under test is a real
 * process on a real platform: `/system/bin/sh` is what runs here, and whether
 * a forked child survives its parent's coroutine is not a JVM question.
 */
@RunWith(AndroidJUnit4::class)
class NativeToolRunnerCancellationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runner = NativeToolRunner(
        toolchain = NativeToolchain.from(context),
        dispatchers = DefaultDispatcherProvider(),
    )

    @Test
    fun cancelling_a_run_stops_the_process_it_started() = runBlocking {
        val marker = File(context.filesDir, "cancellation-marker").apply { delete() }
        // Writes for as long as it lives, and nothing tidies it up: if the
        // process survives the cancel, the file keeps growing and says so.
        val script = "while true; do echo tick >> ${marker.absolutePath}; sleep 1; done"

        val job = launch {
            runner.run(LaunchPlan(listOf(SHELL, "-c", script)), describedAs = "sh")
        }
        val started = waitUntil { marker.length() > 0 }
        assertTrue("the script never ran", started)

        job.cancelAndJoin()

        val whenCancelled = marker.length()
        // Longer than the script's own interval, so a survivor would have
        // written at least once more.
        delay(2_500)
        assertEquals(
            "the process kept running after its run was cancelled",
            whenCancelled,
            marker.length(),
        )
    }

    /**
     * Suspends rather than sleeps, and that is not a style choice: the run
     * being waited for was started with `launch` on this same `runBlocking`
     * dispatcher, so a `Thread.sleep` here blocks the event loop that would
     * have started it -- and the script then never runs at all, which is how
     * the first version of this test failed.
     */
    private suspend fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(100)
        }
        return false
    }

    private companion object {
        const val SHELL = "/system/bin/sh"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
