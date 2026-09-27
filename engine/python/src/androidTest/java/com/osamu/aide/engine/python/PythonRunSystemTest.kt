package com.osamu.aide.engine.python

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.engine.api.RunEvent
import com.osamu.aide.engine.api.RunRequest
import com.osamu.aide.engine.api.RunResult
import com.osamu.aide.engine.api.RunStream
import com.osamu.aide.toolchain.nativetools.LinkerLaunch
import com.osamu.aide.toolchain.nativetools.PythonToolchain
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
 * [PythonRunSystem] against a real CPython, **in the app's own process**.
 *
 * That last part is the point of the file. A hand probe through `adb shell`
 * runs as `shell`, and one through `run-as` runs in `runas_app` -- both of
 * which may `execve` out of storage this app may not, so both will cheerfully
 * demonstrate things the app cannot do. `tools/clang/FINDINGS.md` §7. Only a
 * test here settles a question about exec or SELinux, and the questions this
 * settles are recorded in `tools/python/FINDINGS.md`.
 *
 * It asserts the two guarantees `RunSystem` makes that `BuildSystem` does not:
 * cancelling the collection kills the program, and a failure to start arrives
 * as an ordinary result rather than an exception.
 */
@RunWith(AndroidJUnit4::class)
class PythonRunSystemTest {

    private lateinit var context: Context
    private lateinit var python: PythonToolchain
    private lateinit var engine: PythonRunSystem
    private lateinit var project: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "python")
        install(root)
        assumeTrue(
            "no Python staged: tools/python/fetch-python.sh, then push python.tar to " +
                "${context.getExternalFilesDir(null)}",
            root.resolve("bin").listFiles().orEmpty().any { Regex("""python3\.\d+""").matches(it.name) },
        )
        val launch = LinkerLaunch.forThisProcess()
        assumeTrue("no dynamic linker for this ABI", launch.isAvailable)

        python = PythonToolchain(root, launch)
        project = File(context.filesDir, "python-project").apply { deleteRecursively(); mkdirs() }
        engine = PythonRunSystem(
            python = python,
            dispatchers = DefaultDispatcherProvider(),
            home = File(context.filesDir, "python-home").apply { mkdirs() },
            cache = context.cacheDir,
            packages = File(project, ".aide-packages"),
        )
    }

    private fun install(root: File) {
        if (root.resolve("bin/python3").exists()) return
        val archive = File(context.getExternalFilesDir(null), "python.tar")
        if (!archive.isFile) return
        root.mkdirs()
        // The platform's tar, and a tar rather than a zip, because `bin/python3`
        // is a symlink to the versioned binary and a zip would not carry it.
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun entry(source: String): File =
        File(project, "main.py").apply { writeText(source) }

    @Test
    fun it_runs_a_project_and_streams_what_it_prints() = runBlocking {
        val script = entry("print('hello')\nprint('world')\n")

        val events = engine.run(RunRequest(project, script)).toList()
        val output = events.filterIsInstance<RunEvent.Output>()
        Log.i(TAG, "events=${events.size} output=${output.map { it.line }}")

        assertTrue("nothing was reported as started", events.first() is RunEvent.Started)
        assertEquals(
            listOf("hello", "world"),
            output.filter { it.stream == RunStream.STDOUT }.map { it.line },
        )

        val result = (events.last() as RunEvent.Finished).result
        assertTrue("did not exit cleanly: $result", result is RunResult.Exited && result.succeeded)
    }

    /**
     * **The finding this file exists to pin.**
     *
     * CPython derives `sys.prefix` from `argv[0]`, which under the linker
     * launch is the real binary -- unlike node, which reads `/proc/self/exe`
     * and therefore believes it is `/system/bin/linker64`. So the standard
     * library is found with nothing done about it, and `PythonToolchain` sets
     * no `PYTHONHOME`.
     *
     * Asserted by importing from the standard library rather than by printing
     * `sys.prefix`, because the path is not the claim -- *being able to import*
     * is. A prefix that looked right while `encodings` failed to load would
     * pass a string comparison and leave every project broken.
     */
    @Test
    fun the_standard_library_is_found_without_being_told_where_it_is() = runBlocking {
        val script = entry(
            """
            import json
            import sqlite3
            import ssl
            import sys

            print("prefix", sys.prefix)
            print("executable", sys.executable)
            print("json", json.dumps({"ok": True}))
            print("sqlite", sqlite3.sqlite_version)
            print("ssl", ssl.OPENSSL_VERSION.split()[0])
            """.trimIndent() + "\n",
        )

        val events = engine.run(RunRequest(project, script)).toList()
        val lines = events.filterIsInstance<RunEvent.Output>().map { it.line }
        Log.i(TAG, "stdlib: $lines")

        val result = (events.last() as RunEvent.Finished).result
        assertTrue(
            "the standard library did not load: $lines / $result",
            result is RunResult.Exited && result.succeeded,
        )
        assertTrue("json did not load: $lines", lines.any { it.startsWith("json {") })
        assertTrue("sqlite3 did not load: $lines", lines.any { it.startsWith("sqlite ") })
        // ssl is the one that needs its own shared libraries out of the
        // archive, so it fails where the pure-Python modules do not -- which
        // makes it the module that actually tests LD_LIBRARY_PATH.
        assertTrue("ssl did not load: $lines", lines.any { it.startsWith("ssl OpenSSL") })

        // `sys.executable` is the interpreter and not the linker. Node's
        // equivalent is the linker, and that difference is why this class sets
        // no PYTHONHOME where NodeToolchain has to document a workaround.
        assertTrue(
            "sys.executable was the linker, so the argv[0] finding no longer holds: $lines",
            lines.any { it.startsWith("executable ") && it.endsWith(python.runtime.absolutePath) },
        )
    }

    /**
     * A traceback reaches the log, on stderr, and the exit code is not zero.
     *
     * Both halves have been wrong before in this project's other engines. A
     * process whose stderr is not drained concurrently blocks in the kernel and
     * never exits, and Python makes that likelier than most because a traceback
     * *is* stderr -- so the deadlock would happen exactly when the user most
     * wants to see why their program failed.
     */
    @Test
    fun a_failing_program_reports_its_traceback_and_a_non_zero_exit() = runBlocking {
        val script = entry("print('before')\nraise ValueError('deliberate')\n")

        val events = engine.run(RunRequest(project, script)).toList()
        val stdout = events.filterIsInstance<RunEvent.Output>()
            .filter { it.stream == RunStream.STDOUT }.map { it.line }
        val stderr = events.filterIsInstance<RunEvent.Output>()
            .filter { it.stream == RunStream.STDERR }.map { it.line }
        Log.i(TAG, "stdout=$stdout stderr=$stderr")

        assertEquals(listOf("before"), stdout)
        assertTrue("no traceback on stderr: $stderr", stderr.any { "ValueError: deliberate" in it })

        // `Exited`, not `Failed`: the program *ran* and returned 1, which is
        // an outcome and not a failure to start. Asserting only "not succeeded"
        // would also pass if Python had never started at all.
        val result = (events.last() as RunEvent.Finished).result
        assertTrue("the program never ran: $result", result is RunResult.Exited)
        assertEquals("a raise should exit 1", 1, (result as RunResult.Exited).exitCode)
    }

    /** Arguments reach the program, and the working directory is the project. */
    @Test
    fun it_runs_in_the_project_directory_with_the_arguments_it_was_given() = runBlocking {
        val script = entry(
            """
            import os
            import sys

            with open("written.txt", "w") as out:
                out.write("here")
            print("cwd", os.getcwd())
            print("argv", " ".join(sys.argv[1:]))
            """.trimIndent() + "\n",
        )

        val events = engine
            .run(RunRequest(project, script, arguments = listOf("one", "two")))
            .toList()
        val lines = events.filterIsInstance<RunEvent.Output>().map { it.line }

        assertTrue("arguments did not reach the program: $lines", "argv one two" in lines)
        // A relative path in the program has to land in the project, because
        // that is where the file tree will look for it.
        assertTrue("the run did not write into the project", File(project, "written.txt").isFile)
    }

    /**
     * **Cancelling the collection kills the program.**
     *
     * The guarantee `RunSystem` makes and `BuildSystem` does not. Without it a
     * stopped run leaves a process holding whatever it opened, and the next run
     * of a server fails on a port that nothing visible is using.
     *
     * The script would run for a minute; the collection is abandoned after two
     * seconds, and the timeout returning null *is* the pass -- the flow never
     * completes on its own, so anything else would mean it ran to the end.
     */
    @Test
    fun cancelling_the_collection_stops_the_program() = runBlocking {
        val script = entry(
            """
            import time

            print("started", flush=True)
            time.sleep(60)
            print("should never be reached")
            """.trimIndent() + "\n",
        )

        val seen = mutableListOf<RunEvent>()
        val completed = withTimeoutOrNull(2_000) {
            engine.run(RunRequest(project, script)).toList(seen)
        }

        Log.i(TAG, "cancelled after ${seen.size} events")
        assertEquals("the program ran to completion instead of being cancelled", null, completed)
        assertTrue(
            "the program never started: $seen",
            seen.filterIsInstance<RunEvent.Output>().any { it.line == "started" },
        )
    }

    /**
     * A missing entry point is an ordinary result, not an exception.
     *
     * The caller asked to run a project; being told there is nothing to run is
     * an answer. Throwing would make every caller wrap this in a try.
     */
    @Test
    fun a_missing_entry_point_is_reported_and_not_thrown() = runBlocking {
        val absent = File(project, "not-here.py")

        val events = engine.run(RunRequest(project, absent)).toList()

        val result = (events.single() as RunEvent.Finished).result
        assertTrue("expected a failure, got $result", result is RunResult.Failed)
        assertTrue(
            "the message does not name the path it looked for: $result",
            absent.absolutePath in (result as RunResult.Failed).message,
        )
    }

    /**
     * **Can a Python program start a child?** Measured, not assumed.
     *
     * The shell says yes, and the shell is not the app: `/data/local/tmp` is
     * exec-allowed and app-private storage is not, so a probe through `adb
     * shell` will demonstrate a spawn this process may not perform
     * (`tools/clang/FINDINGS.md` §7). This asks in the only place where the
     * answer counts.
     *
     * Both routes are tried in one run, because the interesting result is the
     * *difference* between them: `sys.executable` is the real binary in
     * app-private storage, and the linker is the route everything else here
     * takes. Whatever the outcome, it is recorded in
     * `tools/python/FINDINGS.md` §7 -- this test exists to keep that section
     * honest rather than to demand a particular answer, so it asserts only
     * that the program itself completed and prints what happened.
     */
    @Test
    fun whether_a_program_can_spawn_a_child_is_measured_here() = runBlocking {
        val script = entry(
            """
            import os
            import subprocess
            import sys

            def attempt(label, command):
                try:
                    done = subprocess.run(command, capture_output=True, text=True, timeout=60)
                    print(label, "rc", done.returncode, "out", done.stdout.strip(),
                          "err", done.stderr.strip()[:120])
                except Exception as error:
                    print(label, "raised", type(error).__name__, str(error)[:120])

            attempt("direct", [sys.executable, "-c", "print(42)"])
            attempt("linker", [
                "/system/bin/linker64", sys.executable, "-c", "print(42)",
            ])
            print("done")
            """.trimIndent() + "\n",
        )

        val events = engine.run(RunRequest(project, script)).toList()
        val lines = events.filterIsInstance<RunEvent.Output>().map { it.line }
        // Logged rather than asserted on: this is the measurement.
        Log.i(TAG, "spawn: ${lines.joinToString(" | ")}")

        val result = (events.last() as RunEvent.Finished).result
        assertTrue(
            "the probe itself did not run: $lines / $result",
            result is RunResult.Exited && result.succeeded,
        )
        assertTrue("the probe did not finish: $lines", "done" in lines)
    }

    /** The archive carries pip, and it is runnable as a module. */
    @Test
    fun pip_is_present_and_runs_as_a_module() = runBlocking {
        assumeTrue("this archive carries no pip", python.hasPip)

        // `--version`, not an install: this asserts pip starts, and an install
        // would make the test depend on the network and on PyPI being up.
        // `PythonRunSystem.pip` appends --target, which --version ignores.
        val events = engine.pip(arguments = listOf("--version"), projectDir = project).toList()
        val lines = events.filterIsInstance<RunEvent.Output>().map { it.line }
        Log.i(TAG, "pip: $lines")

        val result = (events.last() as RunEvent.Finished).result
        assertTrue(
            "pip did not start: $lines / $result",
            result is RunResult.Exited && result.succeeded,
        )
        assertTrue("pip did not report a version: $lines", lines.any { it.startsWith("pip ") })
    }

    private companion object {
        const val TAG = "PythonRunSystemTest"
    }
}
