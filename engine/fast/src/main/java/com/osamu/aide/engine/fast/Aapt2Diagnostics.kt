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
            .mapNotNull { line -> parseLine(line, projectRoot) }
            .toList()

    /**
     * One line, or null if it carried no message.
     *
     * Public because the engine parses aapt2's output as the process writes it,
     * a line at a time, as well as all at once. Both routes go through here on
     * purpose: [ProjectPaths.relativise] is applied in exactly one place, so a
     * streamed diagnostic cannot end up pointing at an absolute cache path while
     * a collected one points at a file the user can open.
     */
    fun parseLine(raw: String, projectRoot: File): Diagnostic? {
        val line = raw.trim()
        // aapt2's own spacing, not a message.
        if (line.isEmpty()) return null

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
