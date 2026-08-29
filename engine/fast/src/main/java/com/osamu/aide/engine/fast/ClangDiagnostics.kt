package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Turns clang's output into structured diagnostics.
 *
 * Close to aapt2's format but not the same, and the difference matters:
 * clang prefixes its *own* failures with the driver's name, so
 * `clang-21: error: unable to execute command` looks exactly like a file called
 * `clang-21` reporting an error at no particular line. Parsed with aapt2's
 * rules it would put a tappable link to a nonexistent file in front of the
 * user. Those lines are kept, without a location.
 *
 * `note:` is mapped to INFO rather than dropped. clang uses notes to say where
 * a type was declared or which candidate it rejected, and for a template error
 * the note is usually the useful half.
 */
object ClangDiagnostics {

    private val LOCATED = Regex(
        """^(?<file>.+?):(?<line>\d+):(?:(?<column>\d+):)?\s*""" +
            """(?<severity>fatal error|error|warning|note):\s*(?<message>.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** `clang-21: error: …`, or `ld.lld: error: …` — the tool, not a file. */
    private val FROM_TOOL = Regex(
        """^(?<tool>clang[^:\s]*|ld[^:\s]*|[a-z0-9_.+-]*lld[^:\s]*):\s*""" +
            """(?<severity>fatal error|error|warning|note):\s*(?<message>.*)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(output: String, projectRoot: File): List<Diagnostic> =
        output.lineSequence().mapNotNull { parseLine(it, projectRoot) }.toList()

    fun parseLine(raw: String, projectRoot: File): Diagnostic? {
        val line = raw.trim()
        if (line.isEmpty()) return null

        // The tool's own failures are checked first. A path can contain a
        // colon, so the located pattern would otherwise claim these.
        FROM_TOOL.matchEntire(line)?.let { match ->
            return Diagnostic(
                severity = severityOf(match.group("severity")),
                message = "${match.group("tool")}: ${match.group("message")}",
            )
        }

        val match = LOCATED.matchEntire(line)
            // Source echoes and carets are not diagnostics of their own, but
            // they are the context for the one above; dropping them is fine
            // because the message already carries what they illustrate.
            ?: return Diagnostic(DiagnosticSeverity.INFO, line)

        return Diagnostic(
            severity = severityOf(match.group("severity")),
            message = match.group("message"),
            file = ProjectPaths.relativise(File(match.group("file")), projectRoot),
            line = match.group("line").toIntOrNull() ?: Diagnostic.UNKNOWN,
            column = match.group("column").toIntOrNull() ?: Diagnostic.UNKNOWN,
        )
    }

    private fun severityOf(raw: String): DiagnosticSeverity = when (raw.lowercase()) {
        "error", "fatal error" -> DiagnosticSeverity.ERROR
        "warning" -> DiagnosticSeverity.WARNING
        else -> DiagnosticSeverity.INFO
    }

    private fun MatchResult.group(name: String): String =
        groups[name]?.value.orEmpty()
}
