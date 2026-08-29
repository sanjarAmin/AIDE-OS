package com.osamu.aide.lsp.nativelsp

import org.junit.Assert.assertEquals
import org.junit.Test

class PositionsTest {

    private val text = "int main() {\n    return 0;\n}\n"

    @Test
    fun `the start of the buffer is the origin`() {
        assertEquals(Positions.Position(0, 0), Positions.of(text, 0))
    }

    @Test
    fun `an offset on a later line counts from that line's start`() {
        // The 'r' of return: line 1, four characters in.
        assertEquals(Positions.Position(1, 4), Positions.of(text, text.indexOf("return")))
    }

    @Test
    fun `a newline belongs to the line it ends`() {
        val atNewline = text.indexOf('\n')
        assertEquals(Positions.Position(0, 12), Positions.of(text, atNewline))
        assertEquals(Positions.Position(1, 0), Positions.of(text, atNewline + 1))
    }

    /**
     * Splitting on `\n` leaves the `\r` on the previous line, so a cursor at
     * the end of a CRLF line would be reported one character past where the
     * user put it.
     */
    @Test
    fun `a carriage return is not counted as a character`() {
        val crlf = "int x;\r\nint y;\r\n"

        assertEquals(Positions.Position(0, 6), Positions.of(crlf, crlf.indexOf('\r')))
    }

    /**
     * Clamped to the end of the buffer. The text ends with a newline, so the
     * end of it is the start of an empty final line -- (3, 0), not the end of
     * the last line with text on it. That is also what an editor shows when
     * the cursor is at the very bottom of a file.
     */
    @Test
    fun `an offset past the end is clamped rather than thrown`() {
        assertEquals(Positions.Position(3, 0), Positions.of(text, 9999))
    }

    @Test
    fun `round trips back to the same offset`() {
        val offset = text.indexOf("return")
        val position = Positions.of(text, offset)

        assertEquals(offset, Positions.offsetOf(text, position.line, position.character))
    }

    /**
     * A character index past the end of its line stops at the line end rather
     * than spilling into the next one, which is what a server reporting a
     * column beyond the text would otherwise do.
     */
    @Test
    fun `a character beyond the line end stops at the line end`() {
        assertEquals(text.indexOf('\n'), Positions.offsetOf(text, 0, 999))
    }
}
