package com.osamu.aide.lsp.nativelsp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The Language Server Protocol's wire format: HTTP-style headers, a blank
 * line, then exactly that many bytes of JSON.
 *
 * Small, and separated from everything else because it is the part that is
 * quietly easy to get wrong. **`Content-Length` counts bytes, not characters**,
 * so anything that decodes the stream before this point -- a `BufferedReader`,
 * an `InputStreamReader` -- will read past the end of one message and swallow
 * the start of the next. That failure does not look like an encoding bug: it
 * looks like the server going silent halfway through a session, which is where
 * anyone debugging it would start looking instead.
 *
 * So the header is read a byte at a time and the body by count.
 */
internal object LspFraming {

    private const val CONTENT_LENGTH = "content-length"

    fun write(out: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    /**
     * Reads one message, or null at end of stream.
     *
     * A truncated message -- the server died mid-write -- is an [EOFException]
     * rather than a null, because "the stream ended cleanly between messages"
     * and "the stream ended in the middle of one" are different situations and
     * only the first is ordinary.
     */
    fun read(input: InputStream): String? {
        var length = -1
        var sawHeader = false
        while (true) {
            val line = readLine(input) ?: return if (sawHeader) throw EOFException("headers truncated") else null
            sawHeader = true
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0 && line.take(separator).trim().lowercase() == CONTENT_LENGTH) {
                length = line.substring(separator + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (length < 0) throw EOFException("a message arrived with no Content-Length")

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) throw EOFException("message body truncated at $read of $length bytes")
            read += n
        }
        return String(body, Charsets.UTF_8)
    }

    /** One header line, without its terminator. Null only at a clean end. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString()
            // Headers are ASCII; a lone CR is a separator, not content.
            if (c != '\r'.code) line.append(c.toChar())
        }
    }
}
