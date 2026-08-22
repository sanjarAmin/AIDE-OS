package com.osamu.aide.engine.fast

import com.android.tools.r8.origin.PathOrigin
import com.android.tools.r8.position.TextPosition
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File
import com.android.tools.r8.Diagnostic as R8Diagnostic

/**
 * Turns D8's diagnostics into ours.
 *
 * Unlike aapt2 and ECJ there is no text to parse: D8 hands over structured
 * objects through a [com.android.tools.r8.DiagnosticsHandler], and the severity
 * is which method it called rather than a word in the message.
 *
 * The location it gives is a **class file**, not a source file -- by the time D8
 * runs, the Java source is two stages behind. That path is reported as-is rather
 * than guessed back to a `.java`: the mapping breaks on nested and anonymous
 * classes, on every Kotlin file whose class name differs from its file name, and
 * a wrong mapping sends the editor confidently to the wrong file. In practice a
 * D8 error is about the whole compilation, not a line the user can go fix.
 */
internal object DexDiagnostics {

    fun convert(
        diagnostic: R8Diagnostic,
        severity: DiagnosticSeverity,
        projectRoot: File,
    ): Diagnostic = Diagnostic(
        severity = severity,
        message = diagnostic.diagnosticMessage.orEmpty(),
        file = (diagnostic.origin as? PathOrigin)
            ?.let { ProjectPaths.relativise(it.path.toFile(), projectRoot) },
        line = (diagnostic.position as? TextPosition)
            ?.line
            // D8 uses this position for "somewhere in this file", where the line
            // is a placeholder rather than a real one. Diagnostic.UNKNOWN says
            // that; a 0 or -1 in the gutter does not.
            ?.takeIf { it > 0 }
            ?: Diagnostic.UNKNOWN,
        column = (diagnostic.position as? TextPosition)
            ?.column
            ?.takeIf { it > 0 }
            ?: Diagnostic.UNKNOWN,
    )
}
