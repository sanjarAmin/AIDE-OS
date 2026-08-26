package com.osamu.aide.ui.workspace

import com.osamu.aide.engine.api.BuildEvent
import com.osamu.aide.engine.api.BuildResult
import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What `run_build` hands back to the model.
 *
 * The summary is the assistant's only view of a build, so everything it leaves
 * out is invisible to it and everything it includes is paid for in context.
 * Both directions have a failure with no symptom: dropping the errors makes the
 * assistant confidently report a build it cannot see, and sending the whole log
 * spends the room the fix needed.
 */
class BuildSummaryTest {

    private val root = File("/projects/Demo")

    private fun error(name: String, line: Int, message: String) = Diagnostic(
        severity = DiagnosticSeverity.ERROR,
        message = message,
        file = File(root, "src/$name"),
        line = line,
        column = 1,
    )

    @Test
    fun a_successful_build_says_so_in_one_line() {
        val summary = listOf<BuildEvent>(
            BuildEvent.StageStarted(BuildStage.COMPILE_JAVA),
            BuildEvent.Note("Resolved 41 artifacts"),
            BuildEvent.Finished(BuildResult.Success(File("app.apk"), durationMillis = 2_760)),
        ).summarise(root)

        assertEquals("The build succeeded.", summary)
    }

    /**
     * The log is deliberately absent.
     *
     * Stage lines and dependency notes help nobody decide anything, and a first
     * resolve alone is dozens of lines.
     */
    @Test
    fun the_stage_log_is_not_forwarded() {
        val summary = listOf<BuildEvent>(
            BuildEvent.Note("Resolved 41 artifacts"),
            BuildEvent.StageCompleted(BuildStage.COMPILE_JAVA, 900),
            BuildEvent.Finished(BuildResult.Success(File("app.apk"), 2_760)),
        ).summarise(root)

        assertFalse(summary, "41 artifacts" in summary)
        assertFalse(summary, "COMPILE" in summary.uppercase().replace("THE BUILD", ""))
    }

    @Test
    fun a_failure_carries_its_errors_with_relative_paths() {
        val summary = listOf(
            BuildEvent.DiagnosticReported(error("Main.java", 12, "cannot find symbol: Widget")),
            BuildEvent.Finished(
                BuildResult.Failure(BuildStage.COMPILE_JAVA, "Compilation failed", 1_200),
            ),
        ).summarise(root)

        assertTrue(summary, "The build failed" in summary)
        assertTrue(summary, "Compilation failed" in summary)
        assertTrue(summary, "src/Main.java:12: error: cannot find symbol: Widget" in summary)
        assertFalse("an absolute path would be refused by the file tools:\n$summary", "/projects/" in summary)
    }

    /**
     * Diagnostics arrive on the stream *and* on the result, and which one is
     * populated depends on the stage that failed. Taking only one silently
     * loses the other's errors -- and the model then answers about a build it
     * was shown half of.
     */
    @Test
    fun errors_reported_only_on_the_result_are_still_included() {
        val onlyOnResult = error("Other.java", 4, "incompatible types")

        val summary = listOf<BuildEvent>(
            BuildEvent.Finished(
                BuildResult.Failure(
                    stage = BuildStage.COMPILE_JAVA,
                    message = "Compilation failed",
                    durationMillis = 1_200,
                    diagnostics = listOf(onlyOnResult),
                ),
            ),
        ).summarise(root)

        assertTrue(summary, "incompatible types" in summary)
    }

    @Test
    fun an_error_reported_twice_is_listed_once() {
        val duplicated = error("Main.java", 12, "cannot find symbol: Widget")

        val summary = listOf(
            BuildEvent.DiagnosticReported(duplicated),
            BuildEvent.Finished(
                BuildResult.Failure(
                    BuildStage.COMPILE_JAVA,
                    "Compilation failed",
                    1_200,
                    listOf(duplicated),
                ),
            ),
        ).summarise(root)

        assertEquals(1, summary.lines().count { "cannot find symbol" in it })
    }

    /** One missing import can cascade; the twentieth error adds nothing. */
    @Test
    fun a_flood_of_errors_is_capped_and_the_cap_is_admitted() {
        val many = (1..30).map { BuildEvent.DiagnosticReported(error("Main.java", it, "boom $it")) }

        val summary = (many + BuildEvent.Finished(
            BuildResult.Failure(BuildStage.COMPILE_JAVA, "Compilation failed", 1_200),
        )).summarise(root)

        assertEquals(20, summary.lines().count { "boom" in it })
        assertTrue(summary, "and 10 more" in summary)
    }

    /** Warnings should not crowd out the error that actually stopped the build. */
    @Test
    fun warnings_are_dropped_when_there_are_errors() {
        val summary = listOf(
            BuildEvent.DiagnosticReported(
                Diagnostic(DiagnosticSeverity.WARNING, "unused import", File(root, "src/A.java"), 3),
            ),
            BuildEvent.DiagnosticReported(error("Main.java", 12, "cannot find symbol")),
            BuildEvent.Finished(
                BuildResult.Failure(BuildStage.COMPILE_JAVA, "Compilation failed", 1_200),
            ),
        ).summarise(root)

        assertTrue(summary, "cannot find symbol" in summary)
        assertFalse(summary, "unused import" in summary)
    }

    /**
     * A cancelled build has no Finished event. Reporting that as a success is
     * the worst available answer: the assistant would tell the user their fix
     * worked without anything having compiled.
     */
    @Test
    fun a_build_that_never_finished_is_not_reported_as_a_success() {
        val summary = listOf<BuildEvent>(
            BuildEvent.StageStarted(BuildStage.COMPILE_JAVA),
        ).summarise(root)

        assertFalse(summary, "succeeded" in summary)
        assertTrue(summary, "did not finish" in summary)
    }
}
