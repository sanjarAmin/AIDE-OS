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
 * [NodeToolchain] against a real Node, through the real runner.
 *
 * Spike R13 established that Node runs at all; this asserts that the class
 * built from what it learned actually drives it -- the same relationship
 * `ClangToolchainOnDeviceTest` has to `:spike:clang`. The distinction matters
 * because the spike sets its own environment by hand, and the whole point of
 * the class is that callers do not have to.
 *
 * Skipped when no Node is staged. Build one with `tools/node/fetch-node.sh` and
 * push `node.tar` to this package's external files directory.
 */
@RunWith(AndroidJUnit4::class)
class NodeToolchainOnDeviceTest {

    private lateinit var context: Context
    private lateinit var node: NodeToolchain
    private lateinit var runner: NativeToolRunner
    private lateinit var work: File

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
        node = NodeToolchain(root, launch!!)
        assertTrue("Node is staged but not usable", node.isInstalled)

        runner = NativeToolRunner(NativeToolchain.from(context), DefaultDispatcherProvider())
        work = File(context.filesDir, "node-work").apply { deleteRecursively(); mkdirs() }
    }

    /** Unpacked by this process; see `tools/clang/FINDINGS.md` §4. */
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

    private fun run(plan: LaunchPlan): Pair<AppResult<ToolResult>, String> = runBlocking {
        val output = StringBuilder()
        val result = runner.run(plan, workingDir = work, describedAs = "node") { line ->
            output.append(line.text).append('\n')
        }
        result to output.toString().trim()
    }

    /** The environment the class supplies is enough; the caller adds nothing. */
    @Test
    fun it_runs_javascript_without_the_caller_setting_anything_up() {
        val (result, output) = run(node.plan(listOf("-e", "console.log(6*7)")))
        Log.i(TAG, "node -e -> $result: $output")

        assertTrue("node did not run: $result $output", result is AppResult.Success)
        assertEquals("42", output)
    }

    /**
     * A project script, with the environment a project needs.
     *
     * `prepareEnvironment` is what stops a caller having to know that Node
     * defaults `HOME` somewhere this app cannot write.
     */
    @Test
    fun it_runs_a_project_script() {
        val script = File(work, "index.js")
        script.writeText("console.log('platform=' + process.platform);")

        val (result, output) = run(
            node.planScript(script, environment = node.prepareEnvironment(work, context.cacheDir)),
        )
        Log.i(TAG, "script -> $result: $output")

        assertTrue("the script did not run: $result $output", result is AppResult.Success)
        assertTrue("wrong output: '$output'", output.contains("platform=android"))
    }

    /**
     * Spawning works when the child is given [NodeToolchain.runtime].
     *
     * The class exists partly to make this the easy spelling: `process.execPath`
     * is the linker under this launch, so the obvious code fails in a way that
     * reads as a refusal to spawn. `tools/node/FINDINGS.md` §2.
     */
    @Test
    fun a_child_process_runs_when_given_the_runtime() {
        val (result, output) = run(
            node.plan(
                listOf(
                    "-e",
                    "const {execFileSync}=require('child_process');" +
                        "const o=execFileSync(process.argv[0],['${node.runtime.absolutePath}'," +
                        "'-e','console.log(6*7)'],{encoding:'utf8',env:process.env});" +
                        "console.log('child said '+o.trim());",
                ),
            ),
        )
        Log.i(TAG, "child -> $result: $output")

        assertTrue("the child was refused: $result $output", result is AppResult.Success)
        assertTrue("no answer from the child: '$output'", output.contains("child said 42"))
    }

    /** npm, run as the JavaScript it is rather than through its shell wrapper. */
    @Test
    fun npm_runs_when_the_archive_carries_it() {
        assumeTrue("this archive has no npm", node.hasNpm)

        val plan = requireNotNull(node.planNpm(listOf("--version")))
        val (result, output) = run(plan)
        Log.i(TAG, "npm --version -> $result: $output")

        assertTrue("npm did not run: $result $output", result is AppResult.Success)
        assertTrue("no version in '$output'", Regex("""\d+\.\d+\.\d+""").containsMatchIn(output))
    }

    /** And says so, rather than failing obscurely, when it does not. */
    @Test
    fun an_archive_without_npm_reports_that_rather_than_failing_later() {
        val bare = File(context.filesDir, "node-without-npm").apply {
            deleteRecursively()
            File(this, "bin").mkdirs()
            File(this, "bin/node").writeText("not really node")
        }
        val without = NodeToolchain(bare, requireNotNull(LinkerLaunch.forThisProcess()))

        assertTrue("a tree with no npm reported that it has some", !without.hasNpm)
        assertEquals("planNpm should refuse rather than plan", null, without.planNpm(listOf("--version")))
    }

    private companion object {
        const val TAG = "NodeToolchain"
    }
}
