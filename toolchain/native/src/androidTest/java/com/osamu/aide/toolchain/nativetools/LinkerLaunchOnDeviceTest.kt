package com.osamu.aide.toolchain.nativetools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * That [LinkerLaunch] is *necessary*, and that it works.
 *
 * Both halves matter. A test that only showed the linker route working would
 * pass just as well on a platform where the app could execute the file itself,
 * and would give no reason for the class to exist -- so the direct attempt is
 * asserted to fail first. If that assertion ever breaks, this is simpler than
 * it needs to be and should be deleted, not worked around.
 *
 * The payload is `/system/bin/toybox`: a real dynamically linked executable
 * that is on every device, so nothing has to be downloaded to run this. Spike
 * R9 established the behaviour with the same payload
 * (`tools/nativeexec/FINDINGS.md`); this pins it in the module that now depends
 * on it.
 *
 * **This has to run in the app's own process.** Under `adb shell run-as` the
 * direct exec below succeeds, because `runas_app` is allowed what
 * `untrusted_app` is not. `tools/clang/FINDINGS.md` §7.
 */
@RunWith(AndroidJUnit4::class)
class LinkerLaunchOnDeviceTest {

    private lateinit var payload: File
    private val launch = LinkerLaunch.forThisProcess()
    private val runner = NativeToolRunner(
        toolchain = NativeToolchain.from(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ),
        dispatchers = DefaultDispatcherProvider(),
    )

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // **Named `toybox`, and the name is load-bearing.** toybox picks its
        // applet from `argv[0]`, so a copy called anything else answers every
        // invocation with exit 127. Under this launch `argv[0]` is the path we
        // hand the linker, which is what makes the name reach the program at
        // all -- the same mechanism that selects C++ mode when clang is invoked
        // as `clang++`. `tools/clang/FINDINGS.md` §5.
        payload = File(context.filesDir, "toybox").apply {
            delete()
            File(SYSTEM_PAYLOAD).inputStream().use { source ->
                outputStream().use { source.copyTo(it) }
            }
            setExecutable(true)
        }
    }

    /**
     * The reason [LinkerLaunch] exists.
     *
     * The file is present, readable and marked executable -- `canExecute()` is
     * true -- and `execve` is still refused, because the refusal is the mount
     * and SELinux policy on app-private storage rather than a permission bit.
     * That gap between what the file says and what the platform allows is why
     * this cannot be detected by inspection.
     */
    @Test
    fun the_app_cannot_execute_what_it_downloaded() {
        assertTrue("the payload should look executable", payload.canExecute())

        val failure = try {
            ProcessBuilder(payload.absolutePath, "echo", "hello").start().waitFor()
            null
        } catch (e: Exception) {
            e
        }

        assertTrue(
            "the app executed a file in its own data directory, so LinkerLaunch " +
                "is no longer necessary and this module should lose it",
            failure != null,
        )
    }

    @Test
    fun the_linker_runs_it() = runTest {
        assertTrue("no dynamic linker on this device", launch.isAvailable)
        val output = StringBuilder()

        val result = runner.run(
            plan = launch.plan(payload, listOf("echo", "aide-os")),
            describedAs = "toybox",
        ) { line -> output.append(line.text) }

        assertTrue("the linker could not start it: $result", result is AppResult.Success)
        assertEquals(0, (result as AppResult.Success).value.exitCode)
        assertEquals("aide-os", output.toString().trim())
    }

    /**
     * That the environment reaches the process, which the whole relocation
     * rests on: a downloaded toolchain finds its own shared libraries only
     * because `LD_LIBRARY_PATH` is set for it.
     *
     * Asserted by reading a variable back out through the launched process
     * rather than by inspecting the plan, because the plan being right and the
     * variable arriving are different claims and only the second one matters.
     */
    @Test
    fun the_environment_reaches_the_launched_process() = runTest {
        val output = StringBuilder()

        runner.run(
            plan = launch.plan(
                executable = payload,
                arguments = listOf("printenv", "AIDE_OS_MARKER"),
                environment = mapOf("AIDE_OS_MARKER" to "reached"),
            ),
            describedAs = "toybox",
        ) { line -> output.append(line.text) }

        assertEquals("reached", output.toString().trim())
    }

    /**
     * The inherited environment survives, rather than being replaced by the
     * plan's additions. `PATH` is read by tools that shell out, and losing it
     * produces failures nowhere near the line that caused them.
     */
    @Test
    fun adding_to_the_environment_does_not_clear_it() = runTest {
        val output = StringBuilder()

        runner.run(
            plan = launch.plan(
                executable = payload,
                arguments = listOf("printenv", "PATH"),
                environment = mapOf("AIDE_OS_MARKER" to "reached"),
            ),
            describedAs = "toybox",
        ) { line -> output.append(line.text) }

        assertFalse("PATH was lost when the plan's environment was applied", output.isEmpty())
    }

    private companion object {
        const val SYSTEM_PAYLOAD = "/system/bin/toybox"
    }
}
