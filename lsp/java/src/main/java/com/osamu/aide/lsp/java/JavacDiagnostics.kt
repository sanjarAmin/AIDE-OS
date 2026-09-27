package com.osamu.aide.lsp.java

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.ProjectPaths
import java.io.File
import javax.tools.JavaFileObject
import javax.tools.Diagnostic as JavacDiagnostic

/**
 * javac's diagnostics, in the shape the editor's gutter already renders.
 *
 * Positions are converted 1-based, because [Diagnostic] treats 0 as "no
 * position" and javac's own `NOPOS` is -1. Paths are made project-relative for
 * the same reason `:engine:fast` does it: an absolute `/data/user/0/...` path
 * is not something a user can act on.
 */
internal object JavacDiagnostics {

    fun of(
        diagnostics: List<JavacDiagnostic<out JavaFileObject>>,
        projectRoot: File,
    ): List<Diagnostic> = diagnostics.map { it.toDiagnostic(projectRoot) }

    private fun JavacDiagnostic<out JavaFileObject>.toDiagnostic(projectRoot: File): Diagnostic {
        val reported = source?.name?.let(::File)
        return Diagnostic(
            severity = when (kind) {
                JavacDiagnostic.Kind.ERROR -> DiagnosticSeverity.ERROR
                JavacDiagnostic.Kind.WARNING,
                JavacDiagnostic.Kind.MANDATORY_WARNING,
                -> DiagnosticSeverity.WARNING

                else -> DiagnosticSeverity.INFO
            },
            // getMessage(null) uses the default locale, which is what the user
            // reads everywhere else in the app.
            message = getMessage(null).orEmpty(),
            file = reported?.let { relativise(it, projectRoot) },
            line = lineNumber.toPosition(),
            column = columnNumber.toPosition(),
        )
    }

    /** javac reports NOPOS as -1 and lines as longs; [Diagnostic] wants 0 and Int. */
    private fun Long.toPosition(): Int =
        if (this <= 0 || this > Int.MAX_VALUE) Diagnostic.UNKNOWN else toInt()

    /** @see ProjectPaths.relativise for why both sides are canonicalised. */
    private fun relativise(file: File, projectRoot: File): File =
        ProjectPaths.relativise(file, projectRoot)
}
