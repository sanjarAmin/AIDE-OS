package com.osamu.aide.toolchain.nativetools

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [MonoToolchain] against a real Mono, through the real runner.
 *
 * Spike R14 established that C# compiles and runs; this asserts that the class
 * built from what it learned drives it, and in particular that a caller gets
 * working managed IO without knowing why it would otherwise be broken.
 *
 * Skipped when no Mono is staged. Build one with `tools/mono/fetch-mono.sh` and
 * push `mono.tar` to this package's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class MonoToolchainOnDeviceTest {

    private lateinit var context: Context
    private lateinit var mono: MonoToolchain
    private lateinit var runner: NativeToolRunner
    private lateinit var work: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "mono")
        install(root)
        assumeTrue(
            "no Mono staged: tools/mono/fetch-mono.sh, then push mono.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(root, "bin/mono-sgen").isFile,
        )

        val launch = LinkerLaunch.forThisProcess()
        assumeTrue("no dynamic linker for this ABI", launch != null)
        mono = MonoToolchain(root, launch!!)
        assertTrue("Mono is staged but not usable", mono.isInstalled)

        runner = NativeToolRunner(NativeToolchain.from(context), DefaultDispatcherProvider())
        work = File(context.filesDir, "mono-work").apply { deleteRecursively(); mkdirs() }
    }

    /** Unpacked by this process; also the only way the symlinks survive. */
    private fun install(root: File) {
        if (File(root, "bin/mono-sgen").isFile) return
        val archive = File(context.getExternalFilesDir(null), "mono.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun run(plan: LaunchPlan): Pair<AppResult<ToolResult>, String> = runBlocking {
        val output = StringBuilder()
        val result = runner.run(plan, workingDir = work, describedAs = "mono") { line ->
            output.append(line.text).append('\n')
        }
        result to output.toString().trim()
    }

    /**
     * The rewritten config is what makes managed IO work, so it is asserted
     * directly rather than only through its effects.
     */
    @Test
    fun the_generated_config_no_longer_names_the_build_time_prefix() {
        val config = requireNotNull(mono.configFor(work)) { "no config was generated" }
        val text = config.readText()

        assertTrue(
            "the config still contains mono's build-time libdir variable",
            !text.contains("\$mono_libdir"),
        )
        assertTrue(
            "the config does not point at the installed lib directory",
            text.contains(File(context.filesDir, "mono/lib").absolutePath),
        )
    }

    /** M10's acceptance test for C#, through the class rather than by hand. */
    @Test
    fun a_console_app_compiles_and_runs() {
        val source = File(work, "Hello.cs")
        source.writeText(
            """
            using System;
            class Hello { static void Main() { Console.WriteLine("hello " + (6 * 7)); } }
            """.trimIndent(),
        )
        val assembly = File(work, "Hello.exe")

        val (compiled, compileOutput) =
            run(mono.planCompile(listOf(source), assembly, work))
        Log.i(TAG, "compile -> $compiled: $compileOutput")
        assertTrue("compilation failed: $compiled $compileOutput", compiled is AppResult.Success)
        assertTrue("no assembly was written", assembly.isFile)

        val (ran, output) = run(mono.planRun(assembly, work))
        Log.i(TAG, "run -> $ran: $output")
        assertTrue("the assembly did not run: $ran $output", ran is AppResult.Success)
        assertEquals("hello 42", output)
    }

    /**
     * And the compiler is really compiling.
     *
     * Without this the test above would pass for a toolchain that never read
     * the input -- which is exactly the state R14 spent an hour in, where every
     * file came back as `CS2001 ... could not be found`. So the assertion is
     * specifically that the failure is a *syntax* error and not that one.
     */
    @Test
    fun a_broken_source_fails_with_a_syntax_error_and_not_a_missing_file() {
        val source = File(work, "Broken.cs").apply { writeText("class Broken { this is not C# }") }

        val (result, output) = run(
            mono.planCompile(listOf(source), File(work, "Broken.exe"), work),
        )
        Log.i(TAG, "broken -> $result: ${output.take(200)}")

        // **A non-zero exit is a *successful run*.** `AppResult.Failure` means
        // the tool could not be started at all; a compiler that rejected its
        // input did exactly what it was asked to.
        val exit = (result as? AppResult.Success)?.value?.exitCode
        assertTrue("mcs could not be started at all: $result", exit != null)
        assertTrue("a broken source compiled: exit=$exit", exit != 0)
        assertTrue("no compiler diagnostic at all: $output", output.contains("error CS"))
        assertTrue(
            "it failed as a missing file, so managed IO is unbound again: $output",
            !output.contains("CS2001"),
        )
    }

    private companion object {
        const val TAG = "MonoToolchain"
    }
}
