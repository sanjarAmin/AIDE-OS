package com.osamu.aide.lsp.nativelsp

/**
 * Between the editor's character offsets and LSP's line/character pairs.
 *
 * The editor counts from the start of the buffer; LSP counts lines from zero
 * and characters from the start of a line. Neither is more correct, and the
 * conversion is unremarkable except for two things worth stating.
 *
 * **LSP characters are UTF-16 code units by default.** Kotlin strings are
 * already UTF-16, so an index into one is the right number -- which is a
 * coincidence worth writing down, because it means the obvious code is correct
 * here and would not be in a language with UTF-8 strings.
 *
 * **A `\r\n` line ending leaves the `\r` on the previous line.** Splitting on
 * `\n` and counting what is left includes it, so a cursor after the last
 * visible character of a CRLF line is one past where the user thinks it is.
 * Rare on a phone and wrong everywhere it happens.
 */
internal object Positions {

    data class Position(val line: Int, val character: Int)

    fun of(text: String, offset: Int): Position {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        var i = 0
        while (i < clamped) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
            i++
        }
        var character = clamped - lineStart
        // Do not count a CR that belongs to the line ending.
        if (character > 0 && clamped <= text.length && lineStart + character <= text.length &&
            character >= 1 && text.getOrNull(lineStart + character - 1) == '\r'
        ) {
            character -= 1
        }
        return Position(line, character)
    }

    fun offsetOf(text: String, line: Int, character: Int): Int {
        var index = 0
        var remaining = line
        while (remaining > 0) {
            val next = text.indexOf('\n', index)
            if (next == -1) return text.length
            index = next + 1
            remaining--
        }
        val lineEnd = text.indexOf('\n', index).let { if (it == -1) text.length else it }
        return (index + character).coerceAtMost(lineEnd)
    }
}
