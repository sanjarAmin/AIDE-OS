package com.osamu.aide.editor

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * How a file's lines were terminated when it was read.
 *
 * Kept so saving can put them back. An editor that silently rewrites every CRLF
 * file as LF turns a one-line edit into a whole-file diff, which is a real cost
 * to anyone whose project has other contributors.
 */
enum class LineEnding(val text: String) {
    LF("\n"),
    CRLF("\r\n");

    companion object {
        /**
         * The dominant ending in [text].
         *
         * Mixed endings exist and are usually an accident; picking the majority
         * and normalising to it is what every editor does, and is better than
         * preserving the mess exactly.
         */
        fun of(text: String): LineEnding {
            // One pass, no allocation. windowed(2) reads more clearly and
            // builds a substring per character, which on the multi-megabyte
            // files this is meant to open is several seconds and a lot of GC.
            var crlf = 0
            var lf = 0
            for (i in text.indices) {
                if (text[i] != '\n') continue
                lf++
                if (i > 0 && text[i - 1] == '\r') crlf++
            }
            return if (crlf > lf - crlf) CRLF else LF
        }
    }
}

/**
 * A file open in the editor.
 *
 * [text] is always LF-normalised, because that is what the editor widget and
 * every language tool expect; [lineEnding] and [encoding] record what the file
 * on disk actually used so that saving does not quietly convert it.
 */
data class SourceDocument(
    val file: File,
    val text: String,
    val encoding: Charset = StandardCharsets.UTF_8,
    val lineEnding: LineEnding = LineEnding.LF,
    /** True when the file began with a byte order mark that must be written back. */
    val hasByteOrderMark: Boolean = false,
) {
    val name: String get() = file.name

    /** What to write to disk for [contents], undoing the LF normalisation. */
    fun encode(contents: String): ByteArray {
        val restored = when (lineEnding) {
            LineEnding.LF -> contents
            LineEnding.CRLF -> contents.replace("\n", "\r\n")
        }
        val body = restored.toByteArray(encoding)
        return if (hasByteOrderMark) UTF8_BOM + body else body
    }

    companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
