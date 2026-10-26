package com.osamu.aide.ui.workspace

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The Logcat tab against the real `logcat`, reading this app's own lines --
 * which it may do without the consent another app's would need.
 */
class LogcatViewModelTest {

    private lateinit var viewModel: LogcatViewModel

    @Before
    fun setUp() {
        val ownUid = Process.myUid()
        // This process stands in for the app being built: its uid is named as
        // that app's package, which its log lines never contain.
        viewModel = LogcatViewModel(
            DefaultDispatcherProvider(),
            packageOf = { uid -> if (uid == ownUid) BUILT_APP else null },
        )
    }

    @After
    fun tearDown() = onMain { viewModel.stop() }

    /**
     * The filter starts as the built app's id, and `-v uid` prints an app's uid
     * as a number, so a line the app logged never contained it: the default
     * filter hid nearly everything the app said.
     */
    @Test
    fun the_filter_matches_the_package_that_wrote_a_line() {
        onMain {
            viewModel.setFilter(BUILT_APP)
            viewModel.start()
        }
        val marker = "aide-logcat-owner-${System.nanoTime()}"
        Log.i(TAG, marker)

        await("the line, matched by its owner") { viewModel.state.value.lines.any { marker in it } }
    }

    /**
     * A read replaced mid-stream ends. It checked whether the *newest* job was
     * active rather than its own, so after Ask again or a quick Resume it read
     * on beside its replacement and every line showed twice.
     */
    @Test
    fun a_restarted_read_shows_each_line_once() {
        onMain { viewModel.start() }
        await("the first read to be reading") { viewModel.state.value.lines.isNotEmpty() }

        // The first read is now inside its blocking readLine, which is where
        // the old one stayed.
        onMain { viewModel.start() }
        await("the second read to be reading") { viewModel.state.value.lines.isNotEmpty() }

        val marker = "aide-logcat-once-${System.nanoTime()}"
        Log.i(TAG, marker)
        await("the marker") { viewModel.state.value.lines.any { marker in it } }
        // Room for a duplicate to arrive, if a second reader is there to add it.
        Thread.sleep(1_500)

        assertEquals(1, viewModel.state.value.lines.count { marker in it })
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; state: ${viewModel.state.value.copy(lines = viewModel.state.value.lines.takeLast(5))}")
    }

    private companion object {
        const val TAG = "LogcatViewModelTest"
        const val BUILT_APP = "com.example.built"
    }
}
