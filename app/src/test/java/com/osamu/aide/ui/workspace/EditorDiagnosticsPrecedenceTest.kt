package com.osamu.aide.ui.workspace

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which diagnostics the gutter shows, when two sources disagree.
 *
 * They disagree constantly and by design: the build describes what was on disk
 * when it ran, and the user has been typing since. Getting the precedence wrong
 * is not a crash, it is the editor showing an error the user already fixed --
 * so it is worth pinning as logic rather than left to be noticed on a device.
 */
class EditorDiagnosticsPrecedenceTest {

    private val main = File("/project/src/Main.java")
    private val other = File("/project/src/Other.java")

    private fun error(message: String) =
        Diagnostic(DiagnosticSeverity.ERROR, message, line = 1, column = 1)

    private fun state(
        activeFile: File?,
        build: List<Diagnostic> = emptyList(),
        analysis: AnalysisUiState = AnalysisUiState(),
    ) = WorkspaceUiState(
        activeFile = activeFile,
        build = BuildUiState(diagnostics = build),
        analysis = analysis,
    )

    @Test
    fun live_analysis_wins_for_the_file_it_is_about() {
        val state = state(
            activeFile = main,
            build = listOf(error("stale from the build")),
            analysis = AnalysisUiState(file = main, diagnostics = listOf(error("fresh"))),
        )

        assertEquals(listOf(error("fresh")), state.editorDiagnostics)
    }

    @Test
    fun the_build_is_used_for_a_file_analysis_has_not_seen() {
        val state = state(
            activeFile = other,
            build = listOf(error("from the build")),
            analysis = AnalysisUiState(file = main, diagnostics = listOf(error("about Main"))),
        )

        assertEquals(listOf(error("from the build")), state.editorDiagnostics)
    }

    @Test
    fun the_build_is_used_before_anything_has_been_analysed() {
        val state = state(activeFile = main, build = listOf(error("from the build")))

        assertEquals(listOf(error("from the build")), state.editorDiagnostics)
    }

    /**
     * An analysis that found nothing must be able to clear the gutter.
     *
     * The tempting implementation -- fall back to the build when the analysis
     * list is empty -- would leave a fixed error on screen forever, because
     * "fixed" and "not analysed" would look the same.
     */
    @Test
    fun an_empty_analysis_clears_the_builds_errors_for_that_file() {
        val state = state(
            activeFile = main,
            build = listOf(error("the user just fixed this")),
            analysis = AnalysisUiState(file = main, diagnostics = emptyList()),
        )

        assertEquals(emptyList<Diagnostic>(), state.editorDiagnostics)
    }
}
