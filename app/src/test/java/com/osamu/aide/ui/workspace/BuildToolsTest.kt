package com.osamu.aide.ui.workspace

import com.osamu.aide.ai.core.ProjectFiles
import com.osamu.aide.ai.core.ProjectToolset
import com.osamu.aide.ai.core.ToolRisk
import com.osamu.aide.core.fs.BuildEngine
import com.osamu.aide.core.fs.Project
import com.osamu.aide.core.fs.SourceLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two build tools as the toolset sees them.
 *
 * `BuildSummaryTest` covers what a build turns into. This covers the parts that
 * are about the tools themselves: that they reach the model in the right order,
 * that neither is gated behind a confirmation, and that both refuse cleanly
 * when there is nothing to report -- a refusal the model can read beats a
 * fabricated answer, and beats an exception that ends the turn.
 */
class BuildToolsTest {

    private val lastBuild = LastBuild()

    // Never called: no test here opens a project, which is the only path that
    // reaches it. Asserting that is the point -- a tool that built anyway with
    // nothing open would be a real bug.
    private val tools = buildTools(
        runBuild = { throw AssertionError("nothing here should start a build") },
        project = { null },
        lastBuild = lastBuild,
    )

    private fun toolset() = ProjectToolset(ProjectFiles(File("/nowhere")), tools)

    private val demo = Project(
        name = "Demo",
        rootDir = File("/projects/Demo"),
        applicationId = "com.example.demo",
        language = SourceLanguage.JAVA,
        engine = BuildEngine.FAST,
        lastOpenedAt = 0L,
    )

    /** With [demo] open; still never builds. */
    private fun openToolset() = ProjectToolset(
        ProjectFiles(demo.rootDir),
        buildTools(
            runBuild = { throw AssertionError("nothing here should start a build") },
            project = { demo },
            lastBuild = lastBuild,
        ),
    )

    @Test
    fun both_tools_are_offered_after_the_file_tools() {
        assertEquals(
            listOf("list_files", "read_file", "grep", "edit_file", "run_build", "read_build_errors"),
            toolset().all().map { it.name },
        )
    }

    /**
     * Neither asks for confirmation.
     *
     * A build writes only to the cache, so the plan's "confirm every mutating
     * tool" does not reach it -- and a prompt here would turn fix-rebuild-check
     * into three taps and teach the user to approve without reading, which is
     * how the one prompt that matters gets waved through.
     */
    @Test
    fun neither_build_tool_is_gated() {
        assertTrue(toolset().all().filter { it.name.contains("build") }.all {
            it.risk == ToolRisk.READ_ONLY
        })
    }

    @Test
    fun building_with_no_project_open_is_refused_rather_than_crashing() = runTest {
        val outcome = toolset().execute("run_build", emptyMap())

        assertTrue(outcome.toString(), outcome is ProjectFiles.Outcome.Refused)
        assertTrue(
            outcome.toString(),
            "No project is open" in (outcome as ProjectFiles.Outcome.Refused).reason,
        )
    }

    /** Reading errors before anything was built says what to do instead. */
    @Test
    fun reading_errors_before_a_build_points_at_run_build() = runTest {
        val outcome = toolset().execute("read_build_errors", emptyMap())

        assertTrue(outcome.toString(), outcome is ProjectFiles.Outcome.Refused)
        assertTrue(
            outcome.toString(),
            "run_build" in (outcome as ProjectFiles.Outcome.Refused).reason,
        )
    }

    /**
     * Recorded by the Build button, read by the assistant: the case the tool's
     * own description names -- "the user has just built" -- and the one that
     * answered "Nothing has been built yet" while the assistant kept the record
     * to itself.
     */
    @Test
    fun a_recorded_build_can_be_read_back_without_rebuilding() = runTest {
        lastBuild.record(demo.rootDir, "The build failed during compile_java: Compilation failed")

        val outcome = openToolset().execute("read_build_errors", emptyMap())

        assertEquals(
            ProjectFiles.Outcome.Ok("The build failed during compile_java: Compilation failed"),
            outcome,
        )
    }

    /** Another project's errors are not this one's answer. */
    @Test
    fun another_projects_build_is_not_reported() = runTest {
        lastBuild.record(File("/projects/Other"), "The build failed: elsewhere")

        val outcome = openToolset().execute("read_build_errors", emptyMap())

        assertTrue(outcome.toString(), outcome is ProjectFiles.Outcome.Refused)
    }
}
