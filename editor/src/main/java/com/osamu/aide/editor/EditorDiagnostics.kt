package com.osamu.aide.editor

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import java.io.File

/**
 * Turns build diagnostics into the marks the editor draws in the gutter.
 *
 * The two models do not line up. A [Diagnostic] carries a 1-based line and
 * column and a path relative to the project root -- which is what a compiler
 * reports and what a build pane needs. The editor wants absolute character
 * indexes into one open buffer. Converting needs the buffer, so it cannot be
 * done where the diagnostics are produced.
 */
object EditorDiagnostics {

    /**
     * The diagnostics that belong to [file], as regions in [content].
     *
     * [projectRoot] is needed because diagnostic paths are relative to it: a
     * bare `values/strings.xml` cannot be told from `values-night/strings.xml`,
     * and matching on file name alone would put a diagnostic on the wrong file.
     */
    fun containerFor(
        diagnostics: List<Diagnostic>,
        file: File,
        projectRoot: File,
        content: Content,
    ): DiagnosticsContainer {
        val container = DiagnosticsContainer()
        val regions = diagnostics
            .filter { it.hasLocation && File(projectRoot, it.file!!.path) == file }
            .mapNotNull { it.toRegion(content) }
        if (regions.isNotEmpty()) container.addDiagnostics(regions)
        return container
    }

    private fun Diagnostic.toRegion(content: Content): DiagnosticRegion? {
        // A diagnostic can name a line past the end of a file the user has
        // edited since the build. Dropping it beats drawing it in the wrong
        // place or throwing.
        val lineIndex = line - 1
        if (lineIndex !in 0 until content.lineCount) return null

        val columnCount = content.getColumnCount(lineIndex)
        val startColumn = (column - 1).coerceIn(0, columnCount)
        val start = content.getCharIndex(lineIndex, startColumn)

        // ECJ reports where a problem starts, not where it ends. Underlining to
        // the end of the line is honest about that -- guessing a token boundary
        // would be wrong more often than not.
        val end = content.getCharIndex(lineIndex, columnCount)

        return DiagnosticRegion(
            start,
            // A zero-width region draws nothing at all, which is the one
            // outcome worse than an over-long underline.
            if (end > start) end else start + 1,
            severity.toSoraSeverity(),
        ).apply { detail = DiagnosticDetail(message) }
    }

    private fun DiagnosticSeverity.toSoraSeverity(): Short = when (this) {
        DiagnosticSeverity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
        DiagnosticSeverity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
        DiagnosticSeverity.INFO -> DiagnosticRegion.SEVERITY_TYPO
    }
}
