package com.osamu.aide.toolchain.nativetools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shape of a linker launch, off-device.
 *
 * Whether the route *works* is a platform question and is asserted on a device
 * by `LinkerLaunchOnDeviceTest`. What is worth pinning here is the argv, which
 * is easy to get subtly wrong and produces confusing failures when it is: the
 * executable has to be an argument to the linker, not the thing executed.
 */
class LinkerLaunchTest {

    private val launch = LinkerLaunch(File(LinkerLaunch.LINKER_64))

    @Test
    fun `the linker is executed and the tool is its first argument`() {
        val plan = launch.plan(File("/data/data/pkg/files/bin/clang-21"), listOf("--version"))

        assertEquals(
            listOf(LinkerLaunch.LINKER_64, "/data/data/pkg/files/bin/clang-21", "--version"),
            plan.command,
        )
    }

    @Test
    fun `library paths are joined the way the linker reads them`() {
        val plan = launch.plan(
            executable = File("/data/data/pkg/files/bin/clang-21"),
            libraryPath = listOf(File("/data/data/pkg/files/lib"), File("/data/data/pkg/files/lib2")),
        )

        assertEquals(
            "/data/data/pkg/files/lib:/data/data/pkg/files/lib2",
            plan.environment[LinkerLaunch.LD_LIBRARY_PATH],
        )
    }

    /**
     * An empty `LD_LIBRARY_PATH` is not the same as an absent one: it is a
     * search path containing the empty string, which the linker reads as the
     * working directory. Setting it blindly would make every run depend on
     * where it was started from.
     */
    @Test
    fun `no library path means the variable is not set at all`() {
        val plan = launch.plan(File("/data/data/pkg/files/bin/tool"))

        assertFalse(LinkerLaunch.LD_LIBRARY_PATH in plan.environment)
    }

    @Test
    fun `extra environment survives beside the library path`() {
        val plan = launch.plan(
            executable = File("/data/data/pkg/files/bin/tool"),
            libraryPath = listOf(File("/lib")),
            environment = mapOf("TMPDIR" to "/data/data/pkg/cache"),
        )

        assertEquals("/data/data/pkg/cache", plan.environment["TMPDIR"])
        assertEquals("/lib", plan.environment[LinkerLaunch.LD_LIBRARY_PATH])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty plan is rejected rather than failing at exec time`() {
        LaunchPlan(emptyList())
    }

    @Test
    fun `a missing linker is reported rather than assumed`() {
        assertFalse(LinkerLaunch(File("/system/bin/no-such-linker")).isAvailable)
    }
}
