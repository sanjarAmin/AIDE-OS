package com.osamu.aide.engine.mono

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.engine.api.RunStream
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.MonoToolchain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [MonoRunSystem] against a real mono.
 *
 * The second implementation of the same contract `:engine:node` implements, and
 * the tests are deliberately the same questions asked of a different runtime:
 * are the streams told apart, is a non-zero exit still a run, does abandoning
 * the collection kill the program. What is new here is the compile step, which
 * has outcomes Node has no equivalent of.
 */
@RunWith(AndroidJUnit4::class)
class MonoRunSystemTest {

    private lateinit var context: Context
    private lateinit var engine: MonoRunSystem
    private lateinit var project: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "mono")
        install(root)
        assumeTrue(
            "no mono staged: tools/mono/fetch-mono.sh, then push mono.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(root, "bin/mono-sgen").isFile,
        )
        val launch = LinkerLaunch.forThisProcess()
        assumeTrue("no dynamic linker for this ABI", launch.isAvailable)

        engine = MonoRunSystem(
            mono = MonoToolchain(root, launch),
            dispatchers = DefaultDispatcherProvider(),
            workspace = File(context.filesDir, "mono-workspace").apply { mkdirs() },
        )
        project = File(context.filesDir, "cs-project").apply { deleteRecursively(); mkdirs() }
    }

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

    /** Writes `Program.cs`; the engine finds it by walking, as it does for a user. */
    private fun program(body: String): File =
        File(project, "Program.cs").apply {
            writeText(
                """
                using System;
                class Program { static void Main(string[] args) { $body } }
                """.trimIndent(),
            )
        }

    private fun request() = RunRequest(project, File(project, "Program.cs"))

    @Test
    fun it_compiles_a_project_and_runs_what_it_produced() = runBlocking {
        program("""Console.WriteLine("hello"); Console.WriteLine("world");""")

        val events = engine.run(request()).toList()
        val output = events.filterIsInstance<RunEvent.Output>()
        Log.i(TAG, "events=${events.size} output=${output.map { it.line }}")

        // Two processes, so two starts: the compiler, then the assembly. A
        // single Started would mean one of the two ran unreported.
        assertEquals(
            "a C# run is a compile and then a run",
            2,
            events.filterIsInstance<RunEvent.Started>().size,
        )
        assertTrue(
            "the assembly was not produced",
            ProjectLayout(project).csharpAssembly.isFile,
        )
        assertEquals(
            listOf("hello", "world"),
            output.filter { it.stream == RunStream.STDOUT }.map { it.line },
        )

        val result = (events.last() as RunEvent.Finished).result
        assertTrue("did not exit cleanly: $result", result is RunResult.Exited && result.succeeded)
    }

    /**
     * A compile error ends the run as [RunResult.Failed], not `Exited(1)`.
     *
     * The distinction the contract is built on: the program never ran, so it
     * cannot have returned anything. `Exited(1)` would be indistinguishable in
     * the status line from a program that ran and chose to fail.
     */
    @Test
    fun a_compile_error_is_a_failure_to_run_and_not_an_exit_code() = runBlocking {
        program("""Console.WriteLine(thisDoesNotExist);""")

        val events = engine.run(request()).toList()
        val result = (events.last() as RunEvent.Finished).result
        val lines = events.filterIsInstance<RunEvent.Output>().map { it.line }
        Log.i(TAG, "compile error -> $result, said ${lines.take(4)}")

        assertTrue("a compile error was reported as an exit code: $result", result is RunResult.Failed)
        // Only the compiler started; nothing was run.
        assertEquals(1, events.filterIsInstance<RunEvent.Started>().size)
        // And the compiler's own diagnostic reached the log, which is what the
        // user acts on -- "Compilation failed." alone would be useless.
        assertTrue(
            "mcs said nothing about the error: $lines",
            lines.any { "CS0103" in it || "thisDoesNotExist" in it },
        )
    }

    /** A non-zero exit is [RunResult.Exited]: the program ran and said no. */
    @Test
    fun a_program_that_exits_non_zero_still_ran() = runBlocking {
        program("Environment.Exit(3);")

        val result = (engine.run(request()).toList().last() as RunEvent.Finished).result
        Log.i(TAG, "non-zero -> $result")

        assertTrue("reported as a failure to start: $result", result is RunResult.Exited)
        assertEquals(3, (result as RunResult.Exited).exitCode)
        assertTrue("claimed success", !result.succeeded)
    }

    /** stderr is tagged, not merged. */
    @Test
    fun the_two_streams_are_told_apart() = runBlocking {
        program("""Console.WriteLine("out"); Console.Error.WriteLine("err");""")

        val output = engine.run(request()).toList().filterIsInstance<RunEvent.Output>()
        Log.i(TAG, "tagged=${output.map { it.stream to it.line }}")

        assertTrue("stdout was not carried", output.any { it.stream == RunStream.STDOUT && it.line == "out" })
        assertTrue("stderr was not carried", output.any { it.stream == RunStream.STDERR && it.line == "err" })
    }

    /** A project with no sources is answered, not thrown. */
    @Test
    fun a_project_with_nothing_to_compile_is_an_ordinary_result() = runBlocking {
        assertTrue("handles() accepted an empty project", !engine.handles(request()))

        val result = (engine.run(request()).toList().last() as RunEvent.Finished).result
        Log.i(TAG, "no sources -> $result")

        assertTrue("an empty project was not reported as a failure: $result", result is RunResult.Failed)
        assertTrue(
            "the message does not name the project: ${(result as RunResult.Failed).message}",
            result.message.contains(project.name),
        )
    }

    /**
     * **Cancelling the collection kills the program.**
     *
     * Asserted a second time, against a second runtime, because the mechanism
     * differs: `:engine:node` waits inside `callbackFlow` and kills in
     * `awaitClose`, this one interrupts a blocking `waitFor`. A guarantee that
     * holds for one implementation of a contract says nothing about the other.
     */
    @Test
    fun abandoning_the_collection_stops_the_program() = runBlocking {
        val marker = File(project, "still-alive.txt")
        program(
            """
            for (int i = 0; i < 600; i++) {
                System.IO.File.WriteAllText(@"${marker.absolutePath}", i.ToString());
                System.Threading.Thread.Sleep(100);
            }
            """.trimIndent(),
        )

        withTimeoutOrNull(30_000) { engine.run(request()).toList() }

        assumeTrue("the program never got going", marker.isFile)
        val lastSeen = marker.readText()
        Thread.sleep(1_500)
        Log.i(TAG, "after cancellation: was=$lastSeen now=${marker.readText()}")

        assertEquals(
            "the program outlived the collection that started it",
            lastSeen,
            marker.readText(),
        )
    }

    private companion object {
        const val TAG = "MonoRunSystem"
    }
}
