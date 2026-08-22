package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Turns ECJ's output into structured diagnostics.
 *
 * The compiler is run with `-Xemacs`, which produces one line per problem:
 *
 * ```
 * /path/to/Main.java:9: error: Type mismatch: cannot convert from String to int
 *     int x = "not an int";
 *             ^^^^^^^^^^^^
 * ```
 *
 * The default format spreads each problem over five lines around a `----------`
 * rule, which is meant for a terminal and is worse to parse and worse to show.
 *
 * Unlike [Aapt2Diagnostics], unmatched lines are **dropped**. Here they are the
 * echoed source line and its carets -- the user already has the source, and on a
 * phone that is two wasted lines per error. The caret would in principle give a
 * column, but ECJ strips the source line's indentation before echoing it, so the
 * caret's position does not correspond to a real column and reporting one would
 * be a fabrication.
 */
internal object EcjDiagnostics {

    private val LINE = Regex(
        """^(?<file>.+?):(?<line>\d+):\s*(?<severity>error|warning):\s*(?<message>.*)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(output: String, projectRoot: File): List<Diagnostic> =
        output.lineSequence()
            .mapNotNull { line -> LINE.matchEntire(line.trim()) }
            .map { match ->
                fun group(name: String) = match.groups[name]?.value.orEmpty()
                Diagnostic(
                    severity = when (group("severity").lowercase()) {
                        "error" -> DiagnosticSeverity.ERROR
                        "warning" -> DiagnosticSeverity.WARNING
                        else -> DiagnosticSeverity.INFO
                    },
                    message = group("message"),
                    file = ProjectPaths.relativise(File(group("file")), projectRoot),
                    line = group("line").toIntOrNull() ?: Diagnostic.UNKNOWN,
                )
            }
            .toList()
}
