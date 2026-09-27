package com.osamu.aide.ui.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **Does `run_shell` really run a command, in the app's own process?**
 *
 * `ShellToolsTest` covers the rules -- offered, mutating, refused without
 * approval -- against an injected runner, so the part that actually matters
 * here has never been exercised: `ProcessBuilder("/system/bin/sh", ...)`
 * starting a child from inside an Android app, with the project directory as
 * its working directory.
 *
 * That is not a question a JVM test can answer. `tools/clang/FINDINGS.md` §7
 * is the reason: `adb shell run-as` runs in a different domain and will
 * cheerfully do things the app cannot, so only a test in the app's own process
 * settles anything about exec. This session added a third way `run-as`
 * differs -- what else is resident -- so the rule is worth restating.
 *
 * What a shell can reach from here is measured separately; this is about
 * whether the tool works and stays inside the project.
 */
@RunWith(AndroidJUnit4::class)
class ShellToolsDeviceTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "shell-${System.nanoTime()}").apply { mkdirs() }
        File(root, "README.md").writeText("hello from the project")
        File(root, "src").mkdirs()
    }

    private fun project() = Project(
        name = "Sample",
        rootDir = root,
        applicationId = "com.example.sample",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    /** The real toolset, with no runner injected: the device path. */
    private fun toolset() = ProjectToolset(ProjectFiles(root), shellTools(project = { project() }))

    private fun run(command: String, approved: Boolean = true): ProjectFiles.Outcome = runBlocking {
        toolset().execute("run_shell", mapOf("command" to command), approved)
    }

    @Test
    fun a_command_runs_and_its_output_comes_back() {
        val outcome = run("echo hello-from-shell")

        assertTrue("run_shell was refused: $outcome", outcome is ProjectFiles.Outcome.Ok)
        assertTrue(
            "output was ${(outcome as ProjectFiles.Outcome.Ok).content}",
            outcome.content.contains("hello-from-shell"),
        )
    }

    /**
     * **The working directory is the project, not the app's.**
     *
     * The whole point of the tool: a model that asks to list files expects the
     * user's project. Without the `directory(...)` call the command would run
     * wherever the app process happens to be -- which is `/`, and reads as an
     * empty project.
     */
    @Test
    fun the_command_runs_in_the_project_directory() {
        val outcome = run("ls")

        val content = (outcome as ProjectFiles.Outcome.Ok).content
        assertTrue("did not see the project's files: $content", content.contains("README.md"))
        assertTrue(content.contains("src"))
    }

    @Test
    fun a_command_that_fails_reports_its_exit_code_and_stderr() {
        // An honest failure matters more than a clean one: the model has to be
        // told it failed, or it reports success to the user.
        val outcome = run("cat no-such-file")

        val content = when (outcome) {
            is ProjectFiles.Outcome.Ok -> outcome.content
            is ProjectFiles.Outcome.Refused -> outcome.reason
        }
        assertTrue("no exit code in: $content", content.contains("exit code"))
        assertTrue("no stderr in: $content", content.contains("No such file", ignoreCase = true))
    }

    @Test
    fun a_command_with_no_output_says_so_rather_than_nothing() {
        // An empty string reads to a model as a tool that did not work.
        val outcome = run("true")

        assertTrue((outcome as ProjectFiles.Outcome.Ok).content.contains("no output"))
    }

    @Test
    fun output_is_bounded() {
        // A `find /` or a cat of a binary would otherwise fill the context
        // window -- and on a local model, the context is 4096 tokens.
        val outcome = run("yes abcdefghij | head -c 40000")

        val content = (outcome as ProjectFiles.Outcome.Ok).content
        assertTrue("output was ${content.length} chars", content.length < 20_000)
        assertTrue("no truncation notice: ${content.takeLast(80)}", content.contains("truncated"))
    }

    /**
     * A command that never ends must not hold the turn open forever.
     *
     * Thirty seconds is the tool's own limit; this uses a sleep well past it
     * and asserts the refusal names the timeout, because "something went wrong"
     * leaves the model to guess.
     */
    @Test
    fun a_hanging_command_is_stopped() {
        val started = System.currentTimeMillis()
        val outcome = run("sleep 120")
        val elapsed = System.currentTimeMillis() - started

        assertTrue("came back as $outcome", outcome is ProjectFiles.Outcome.Refused)
        assertTrue("timed out", (outcome as ProjectFiles.Outcome.Refused).reason.contains("timed out"))
        assertTrue("took ${elapsed}ms, so the timeout did not fire", elapsed < 60_000)
    }

    /**
     * **The gate is real on the device too.**
     *
     * `ShellToolsTest` asserts this against a fake runner, where "refused"
     * costs nothing. Here a refusal has to mean no process was started, which
     * is the property the approval prompt is actually protecting.
     */
    @Test
    fun a_declined_command_does_not_run() {
        val marker = File(root, "should-not-exist")

        val outcome = run("touch ${marker.name}", approved = false)

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertFalse("the command ran anyway", marker.exists())
    }

    @Test
    fun an_approved_command_can_change_the_project() {
        // The other direction, so the test above cannot pass by the tool being
        // broken: approval really does let it write.
        val marker = File(root, "made-by-the-assistant")

        run("touch ${marker.name}")

        assertTrue("approved command did not run", marker.exists())
    }

    @Test
    fun run_shell_is_still_declared_mutating() {
        // The risk is what puts the prompt on screen. If this ever flips to
        // READ_ONLY the assistant gets a shell with no gate at all.
        assertEquals(ToolRisk.MUTATING, toolset().find("run_shell")?.risk)
    }
}
