package com.osamu.aide.spike.nodejs

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Node.js, started from app-private storage.
 *
 * The whole of M10's JavaScript half rests on this working, so it is asked
 * before anything is designed around it. See the module's `build.gradle.kts`
 * for why the answer is not implied by the JDK and clang spikes.
 *
 * **`adb shell run-as` cannot answer any of this.** It runs in `runas_app`,
 * which *is* permitted to execute from app storage, so a hand probe will
 * cheerfully contradict every assertion here. `tools/clang/FINDINGS.md` §7.
 */
@RunWith(AndroidJUnit4::class)
class NodeOnDeviceTest {

    private lateinit var context: Context
    private lateinit var prefix: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.getExternalFilesDir(null), ARCHIVE)
        assumeTrue(
            "no Node staged: build one with tools/node/fetch-node.sh and push " +
                "$ARCHIVE to ${context.getExternalFilesDir(null)}",
            archive.isFile,
        )

        prefix = File(context.filesDir, "node")
        if (!File(prefix, "bin/node").canRead()) {
            prefix.mkdirs()
            // **Unpacked by a child of this process, not by adb.** A file
            // written by the shell carries the shell's SELinux label and the
            // app cannot use it; a child of the app inherits the app's domain.
            // `tools/clang/FINDINGS.md` §4, and the same reason
            // :toolchain:manager unpacks in process.
            ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", prefix.absolutePath)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
        }
        assumeTrue("the archive unpacked without a node binary", File(prefix, "bin/node").canRead())
    }

    private data class Run(val exit: Int, val output: String, val millis: Long)

    /**
     * Starts `node` through the dynamic linker.
     *
     * **`LD_LIBRARY_PATH` is not optional.** The binary's `RUNPATH` is
     * `/data/data/com.termux/files/usr/lib`, a prefix this app neither has nor
     * can create, so without the path set every run dies with
     * `library "libicuuc.so.78" not found` -- which reads as a missing file
     * rather than a directory that was never searched.
     */
    private fun node(vararg arguments: String, timeoutSeconds: Long = 120): Run {
        val started = System.currentTimeMillis()
        val builder = ProcessBuilder(
            listOf(LINKER, File(prefix, "bin/node").absolutePath) + arguments,
        ).redirectErrorStream(true)
        builder.directory(context.filesDir)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        val process = builder.start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Run(-1, "timed out after ${timeoutSeconds}s", System.currentTimeMillis() - started)
        }
        return Run(process.exitValue(), text, System.currentTimeMillis() - started)
    }

    @Test
    fun the_runtime_is_an_android_binary_and_not_a_linux_one() {
        val binary = File(prefix, "bin/node")
        assertTrue("no node binary", binary.canRead())
        val header = binary.inputStream().use { it.readNBytes(20) }
        // ELF, 64-bit, little endian. A glibc build from nodejs.org would pass
        // this too; what it would fail is every test below.
        assertEquals("not an ELF file", 0x7F.toByte(), header[0])
        assertEquals('E'.code.toByte(), header[1])
        Log.i(TAG, "node is ${binary.length() / 1_000_000} MB")
    }

    /** Question 1: does it start at all, from a directory the app may not exec? */
    @Test
    fun node_starts_and_reports_its_version() {
        val run = node("--version")
        Log.i(TAG, "node --version -> exit=${run.exit} in ${run.millis} ms: ${run.output}")

        assertEquals("node did not start: ${run.output}", 0, run.exit)
        assertTrue("no version in '${run.output}'", run.output.startsWith("v"))
    }

    /** Question 2: does it actually execute JavaScript, or merely start? */
    @Test
    fun it_evaluates_javascript_and_returns_the_answer() {
        val run = node("-e", "console.log(6*7)")
        Log.i(TAG, "node -e -> exit=${run.exit}: ${run.output}")

        assertEquals("evaluation failed: ${run.output}", 0, run.exit)
        assertEquals("42", run.output)
    }

    /**
     * Question 3: a real script from disk, which is what a project is.
     *
     * `-e` proves the engine runs. Reading a file proves the module loader and
     * the filesystem layer work under this launch, which is what a `node
     * index.js` in somebody's project needs.
     */
    @Test
    fun it_runs_a_script_from_the_project_directory() {
        val project = File(context.filesDir, "js-project").apply { mkdirs() }
        File(project, "index.js").writeText(
            """
            const os = require('os');
            const fs = require('fs');
            fs.writeFileSync('out.txt', 'written by node');
            console.log('platform=' + process.platform + ' arch=' + process.arch);
            """.trimIndent(),
        )

        val run = node(File(project, "index.js").absolutePath)
        Log.i(TAG, "script -> exit=${run.exit}: ${run.output}")

        assertEquals("the script failed: ${run.output}", 0, run.exit)
        assertTrue("no platform line in '${run.output}'", run.output.contains("platform=android"))
        // Written relative to the working directory, which is filesDir.
        assertEquals(
            "node could not write a file",
            "written by node",
            File(context.filesDir, "out.txt").readText(),
        )
    }

    /**
     * Question 4: can it spawn a child? **Yes -- but not itself.**
     *
     * This is the one that was genuinely open, because clang cannot spawn
     * anything here: a child would have to `execve` out of app storage, which
     * the app's domain forbids, and that single fact reshaped M7 into planning
     * links with `-###` and running them ourselves.
     *
     * Node is not in that position, and the first reading of the result said it
     * was. Spawning `process.execPath` fails with
     *
     * ```
     * Command failed: /apex/com.android.runtime/bin/linker64 -e console.log(1)
     * error: expected absolute path: "-e"
     * ```
     *
     * which is not a refusal at all. **`process.execPath` is the linker**, not
     * `bin/node`: under this launch `/proc/self/exe` points at
     * `/system/bin/linker64`, so Node reports the linker as its own path and
     * re-invokes it with Node's arguments. The linker ran -- the spawn was
     * permitted -- and then complained about `-e` because that is not a path.
     * `LinkerLaunch` names this as one of the two costs of the route, and the
     * JDK hits the identical wall: its launcher re-execs itself and dies with
     * the same `expected absolute path`.
     *
     * So the two things are separated here. Spawning through the linker with
     * the real binary works, which means `npm` -- a script Node runs -- and
     * anything else that shells out are **not** off the table for M10. What is
     * needed is that Node be told where it really is.
     */
    @Test
    fun it_can_spawn_a_child_when_given_the_real_path() {
        val real = File(prefix, "bin/node").absolutePath
        val run = node(
            "-e",
            "const {execFileSync}=require('child_process');" +
                "const o=execFileSync('$LINKER',['$real','-e','console.log(6*7)']," +
                "{encoding:'utf8',env:process.env});" +
                "console.log('child said '+o.trim());",
        )
        Log.i(TAG, "spawn via linker -> exit=${run.exit}: ${run.output}")

        assertEquals("a child process was refused outright: ${run.output}", 0, run.exit)
        assertTrue("the child produced nothing: ${run.output}", run.output.contains("child said 42"))
    }

    /**
     * And the reason the obvious spelling fails, pinned so it is not
     * rediscovered as a refusal.
     */
    @Test
    fun exec_path_is_the_linker_and_not_the_runtime() {
        val run = node("-e", "console.log(process.execPath)")
        Log.i(TAG, "execPath -> ${run.output}")

        assertEquals(0, run.exit)
        assertTrue(
            "execPath is '${run.output}', so this route no longer misreports it -- " +
                "delete this test and the workaround it documents",
            run.output.contains("linker"),
        )
    }

    private companion object {
        const val TAG = "NodeSpike"
        const val ARCHIVE = "node.tar"
        const val LINKER = "/system/bin/linker64"
    }
}
