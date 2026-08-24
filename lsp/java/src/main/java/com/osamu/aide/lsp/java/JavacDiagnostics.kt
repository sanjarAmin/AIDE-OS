package com.osamu.aide.lsp.java

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
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

    /**
     * Both sides canonicalised, for the reason `:engine:fast` learned the hard
     * way: `/data/user/0/<pkg>` and `/data/data/<pkg>` are the same directory
     * reached by two paths, and a plain prefix match silently fails.
     */
    private fun relativise(file: File, projectRoot: File): File {
        val path = canonical(file)
        val prefix = canonical(projectRoot).trimEnd('/') + "/"
        return if (path.startsWith(prefix)) File(path.removePrefix(prefix)) else file
    }

    private fun canonical(file: File): String =
        runCatching { file.canonicalFile }.getOrDefault(file).invariantSeparatorsPath
}
