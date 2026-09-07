package com.osamu.aide.engine.node

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
import com.osamu.aide.toolchain.nativetools.NodeToolchain
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
 * [NodeRunSystem] against a real Node.
 *
 * Asserts the two guarantees this contract makes that [com.osamu.aide.engine.api.BuildSystem]
 * does not: cancelling the collection kills the program, and a failure to start
 * arrives as an ordinary result rather than an exception.
 */
@RunWith(AndroidJUnit4::class)
class NodeRunSystemTest {

    private lateinit var context: Context
    private lateinit var engine: NodeRunSystem
    private lateinit var project: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "node")
        install(root)
        assumeTrue(
            "no Node staged: tools/node/fetch-node.sh, then push node.tar to " +
                "${context.getExternalFilesDir(null)}",
            File(root, "bin/node").isFile,
        )
        val launch = LinkerLaunch.forThisProcess()
        assumeTrue("no dynamic linker for this ABI", launch != null)

        engine = NodeRunSystem(
            node = NodeToolchain(root, launch!!),
            dispatchers = DefaultDispatcherProvider(),
            home = File(context.filesDir, "node-home").apply { mkdirs() },
            cache = context.cacheDir,
        )
        project = File(context.filesDir, "run-project").apply { deleteRecursively(); mkdirs() }
    }

    private fun install(root: File) {
        if (File(root, "bin/node").isFile) return
        val archive = File(context.getExternalFilesDir(null), "node.tar")
        if (!archive.isFile) return
        root.mkdirs()
        ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", root.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
    }

    private fun entry(source: String): File =
        File(project, "index.js").apply { writeText(source) }

    @Test
    fun it_runs_a_project_and_streams_what_it_prints() = runBlocking {
        val script = entry("console.log('hello'); console.log('world');")

        val events = engine.run(RunRequest(project, script)).toList()
        val output = events.filterIsInstance<RunEvent.Output>()
        Log.i(TAG, "events=${events.size} output=${output.map { it.line }}")

        assertTrue("nothing was reported as started", events.first() is RunEvent.Started)
        assertEquals(listOf("hello", "world"), output.filter { it.stream == RunStream.STDOUT }.map { it.line })

        val result = (events.last() as RunEvent.Finished).result
        assertTrue("did not exit cleanly: $result", result is RunResult.Exited && result.succeeded)
    }

    /** stderr is tagged, not merged, so a caller does not colour output as errors. */
    @Test
    fun the_two_streams_are_told_apart() = runBlocking {
        val script = entry("console.log('out'); console.error('err');")

        val output = engine.run(RunRequest(project, script)).toList()
            .filterIsInstance<RunEvent.Output>()
        Log.i(TAG, "tagged=${output.map { it.stream to it.line }}")

        assertEquals(listOf("out"), output.filter { it.stream == RunStream.STDOUT }.map { it.line })
        assertEquals(listOf("err"), output.filter { it.stream == RunStream.STDERR }.map { it.line })
    }

    /**
     * A non-zero exit is [RunResult.Exited], not [RunResult.Failed].
     *
     * The program ran and said no. `Failed` is reserved for never having
     * started, and conflating the two is a mistake this project has already
     * made in the other direction.
     */
    @Test
    fun a_program_that_exits_non_zero_still_ran() = runBlocking {
        val script = entry("process.exit(3);")

        val result = (engine.run(RunRequest(project, script)).toList().last() as RunEvent.Finished).result
        Log.i(TAG, "non-zero -> $result")

        assertTrue("reported as a failure to start: $result", result is RunResult.Exited)
        assertEquals(3, (result as RunResult.Exited).exitCode)
        assertTrue("claimed success", !result.succeeded)
    }

    /** A missing entry point is answered, not thrown, and named. */
    @Test
    fun a_missing_entry_point_is_an_ordinary_result() = runBlocking {
        val absent = File(project, "nothing-here.js")

        assertTrue("handles() accepted a project with no entry point", !engine.handles(RunRequest(project, absent)))

        val result = (engine.run(RunRequest(project, absent)).toList().last() as RunEvent.Finished).result
        Log.i(TAG, "missing entry -> $result")

        assertTrue("a missing entry point was not reported as a failure: $result", result is RunResult.Failed)
        assertTrue(
            "the message does not name the file: ${(result as RunResult.Failed).message}",
            result.message.contains("nothing-here.js"),
        )
    }

    /**
     * **Cancelling the collection kills the program.**
     *
     * The guarantee a build does not make. A run that outlived its collector
     * would leave a server holding its port, and the next attempt fails on
     * something the user cannot see.
     */
    @Test
    fun abandoning_the_collection_stops_the_program() = runBlocking {
        val marker = File(project, "still-alive.txt")
        // Writes every 100 ms for a minute. If the process survives the
        // collection being cancelled, the file keeps moving.
        val script = entry(
            """
            const fs = require('fs');
            setInterval(() => fs.writeFileSync('${marker.absolutePath}', String(Date.now())), 100);
            setTimeout(() => process.exit(0), 60000);
            """.trimIndent(),
        )

        // Collect only until the first line of output, then walk away.
        withTimeoutOrNull(20_000) {
            engine.run(RunRequest(project, script)).toList()
        }

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
        const val TAG = "NodeRunSystem"
    }
}
