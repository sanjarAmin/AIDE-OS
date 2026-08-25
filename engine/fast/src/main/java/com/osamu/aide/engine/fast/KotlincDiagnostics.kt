package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Turns kotlinc's output into structured diagnostics.
 *
 * The CLI's default format is `file:line:column: severity: message`, which is
 * close to aapt2's but not identical -- kotlinc always emits a column, and
 * prefixes some lines with `w:` or `e:` shorthand instead of a word. Both forms
 * appear in one run, so both are matched.
 *
 * Unmatched lines are dropped rather than kept as INFO. Unlike aapt2, kotlinc is
 * conversational: it prints its own progress, plugin banners and occasionally a
 * blank warning summary, and surfacing those as diagnostics would bury the real
 * ones in the gutter.
 */
internal object KotlincDiagnostics {

    private val LINE = Regex(
        """^(?:(?<short>[we]):\s*)?""" +
            """(?:(?<file>[^:]+):(?<line>\d+):(?<column>\d+):?\s*)?""" +
            """(?:(?<severity>error|warning|info):\s*)?(?<message>.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(output: String, projectRoot: File): List<Diagnostic> =
        output.lineSequence().mapNotNull { parseLine(it, projectRoot) }.toList()

    private fun parseLine(raw: String, projectRoot: File): Diagnostic? {
        val line = raw.trim()
        if (line.isEmpty()) return null

        val match = LINE.matchEntire(line) ?: return null
        fun group(name: String) = match.groups[name]?.value?.takeIf { it.isNotBlank() }

        val severity = when {
            group("severity")?.lowercase() == "error" -> DiagnosticSeverity.ERROR
            group("severity")?.lowercase() == "warning" -> DiagnosticSeverity.WARNING
            group("short")?.lowercase() == "e" -> DiagnosticSeverity.ERROR
            group("short")?.lowercase() == "w" -> DiagnosticSeverity.WARNING
            // Chatter, not a diagnostic. See the class comment.
            else -> return null
        }

        val file = group("file")?.let { ProjectPaths.relativise(File(it), projectRoot) }
        return Diagnostic(
            severity = severity,
            message = match.groups["message"]?.value?.trim().orEmpty(),
            file = file,
            line = group("line")?.toIntOrNull() ?: Diagnostic.UNKNOWN,
            column = group("column")?.toIntOrNull() ?: Diagnostic.UNKNOWN,
        )
    }
}
