package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Turns aapt2's output into structured diagnostics.
 *
 * aapt2 writes everything to stderr, success included, and its messages are
 * loosely structured: a location is optional, and so is a column. Anything that
 * does not parse is kept as an [DiagnosticSeverity.INFO] line rather than
 * dropped -- an unrecognised message is still the only explanation the user is
 * going to get.
 */
object Aapt2Diagnostics {

    private val LINE = Regex(
        """^(?:(?<file>[^:]*[^:\s][^:]*):(?:(?<line>\d+):)?(?:(?<column>\d+):)?\s*)?""" +
            """(?<severity>error|warning|note):\s*(?<message>.*)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(output: String, projectRoot: File): List<Diagnostic> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> parseLine(line, projectRoot) }
            .toList()

    private fun parseLine(line: String, projectRoot: File): Diagnostic {
        val match = LINE.matchEntire(line)
            ?: return Diagnostic(DiagnosticSeverity.INFO, line)

        fun group(name: String) = match.groups[name]?.value?.takeIf { it.isNotBlank() }

        return Diagnostic(
            severity = when (group("severity")?.lowercase()) {
                "error" -> DiagnosticSeverity.ERROR
                "warning" -> DiagnosticSeverity.WARNING
                else -> DiagnosticSeverity.INFO
            },
            message = match.groups["message"]?.value.orEmpty(),
            file = group("file")?.let { ProjectPaths.relativise(File(it), projectRoot) },
            line = group("line")?.toIntOrNull() ?: Diagnostic.UNKNOWN,
            column = group("column")?.toIntOrNull() ?: Diagnostic.UNKNOWN,
        )
    }
}
