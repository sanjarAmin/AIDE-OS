package com.osamu.aide.lsp.node

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import java.io.File

/**
 * Turns what `node --check` prints into a [Diagnostic].
 *
 * Separated from the service so it can be tested without a device: the parsing
 * is where the mistakes are, and node's output is a fixed shape that a JVM test
 * can hold as a string. The service around it is a `ProcessBuilder`.
 *
 * The shape, for one error and never more than one -- node stops at the first:
 *
 * ```
 * /path/to/index.js:3
 *   return a;
 *          ^
 *
 * SyntaxError: Unexpected identifier 'a'
 *     at wrapSafe (node:internal/modules/cjs/loader:1866:18)
 * ```
 *
 * Three lines carry information and they are not adjacent: the path and line
 * are on the first, the **column is the offset of the caret** in the third, and
 * the message is the first line after the blank one. The frames below it are
 * node's own internals and say nothing about the user's file.
 */
object NodeSyntaxCheck {

    /**
     * The diagnostic in [output], reported against [file].
     *
     * [file] rather than the path node printed: the check runs on a copy of the
     * buffer, so what node names is a scratch file the user has never heard of.
     * Empty when the output holds no error, which includes the ordinary case of
     * a file that is fine.
     */
    fun parse(output: String, file: File): List<Diagnostic> {
        val lines = output.lines()
        val header = lines.indexOfFirst { HEADER.matches(it) }
        if (header == -1) return emptyList()

        val line = HEADER.find(lines[header])?.groupValues?.get(1)?.toIntOrNull()
            ?: Diagnostic.UNKNOWN
        // The caret line, if it is where it always is. Guarded rather than
        // assumed: a file whose first line is the error has no source line
        // above the caret, and an off-by-one here would report column 0 on
        // every diagnostic and look like the parser working.
        val column = lines.getOrNull(header + CARET_OFFSET)
            ?.indexOf('^')
            ?.takeIf { it >= 0 }
            ?.plus(1)
            ?: Diagnostic.UNKNOWN

        val message = lines.drop(header + 1)
            .firstOrNull { MESSAGE.containsMatchIn(it) }
            ?.trim()
            ?: return emptyList()

        return listOf(
            Diagnostic(
                severity = DiagnosticSeverity.ERROR,
                message = message,
                file = file,
                line = line,
                column = column,
            ),
        )
    }

    /** Header, source line, caret. Two below the header, never one or three. */
    private const val CARET_OFFSET = 2

    /** `/path/to/index.js:3` -- the path may contain colons, so anchor on the end. */
    private val HEADER = Regex("""^.+:(\d+)$""")

    /**
     * Every error `--check` can raise is one of these.
     *
     * Matched by name rather than by position, because the stack frames below
     * it also end in `:line:column` and would satisfy anything looser.
     */
    private val MESSAGE = Regex("""^(SyntaxError|ReferenceError|TypeError|RangeError):""")
}
