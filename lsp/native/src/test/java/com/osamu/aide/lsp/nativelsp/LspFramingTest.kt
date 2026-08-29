package com.osamu.aide.lsp.nativelsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class LspFramingTest {

    private fun streamOf(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `a message round-trips`() {
        val out = ByteArrayOutputStream()
        LspFraming.write(out, """{"jsonrpc":"2.0"}""")

        assertEquals(
            "Content-Length: 17\r\n\r\n{\"jsonrpc\":\"2.0\"}",
            out.toString(Charsets.UTF_8.name()),
        )
        assertEquals("""{"jsonrpc":"2.0"}""", LspFraming.read(streamOf(out.toString(Charsets.UTF_8.name()))))
    }

    /**
     * The failure this class exists to prevent.
     *
     * `Content-Length` counts bytes. A body with any non-ASCII character has
     * more bytes than characters, so a reader that counted characters would
     * stop short and leave the tail of one message at the head of the next --
     * which does not look like an encoding fault, it looks like the server
     * going silent mid-session.
     */
    @Test
    fun `a multibyte body is measured in bytes, and the next message survives`() {
        val out = ByteArrayOutputStream()
        LspFraming.write(out, """{"m":"héllo — ✓"}""")
        LspFraming.write(out, """{"m":"second"}""")
        val input = ByteArrayInputStream(out.toByteArray())

        assertEquals("""{"m":"héllo — ✓"}""", LspFraming.read(input))
        assertEquals("""{"m":"second"}""", LspFraming.read(input))
        assertNull(LspFraming.read(input))
    }

    @Test
    fun `headers are matched case-insensitively and extra ones ignored`() {
        val message = "content-length: 2\r\nContent-Type: application/vscode-jsonrpc\r\n\r\n{}"

        assertEquals("{}", LspFraming.read(streamOf(message)))
    }

    @Test
    fun `a clean end of stream between messages is not an error`() {
        assertNull(LspFraming.read(streamOf("")))
    }

    /**
     * A server that died mid-write is a different situation from one that
     * closed cleanly, and only the second is ordinary. Collapsing them would
     * make a crash look like a normal shutdown.
     */
    @Test(expected = EOFException::class)
    fun `a truncated body is an error, not an end of stream`() {
        LspFraming.read(streamOf("Content-Length: 50\r\n\r\n{\"short\":true}"))
    }

    @Test(expected = EOFException::class)
    fun `a message with no content length is an error`() {
        LspFraming.read(streamOf("X-Nonsense: 1\r\n\r\n{}"))
    }
}
