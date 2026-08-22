package com.osamu.aide.editor

import com.osamu.aide.core.common.AppError
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Reads and writes the files the editor has open.
 *
 * Kept apart from the editor widget so that opening a file is testable without
 * a display, and so the rules below live in one place rather than in a view.
 */
class DocumentStore(
    private val dispatchers: DispatcherProvider,
    /**
     * Files larger than this are refused.
     *
     * sora-editor copes with large documents, but the whole file is held as a
     * String and every language tool downstream holds its own copy. On a phone
     * the failure is an OOM that kills the process and loses every other open
     * file, which is far worse than declining to open one.
     */
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
) {

    suspend fun open(file: File): AppResult<SourceDocument> = withContext(dispatchers.io) {
        when {
            !file.isFile -> failure("${file.name} does not exist.")
            file.length() > maximumBytes ->
                failure("${file.name} is ${file.length() / MEGABYTE} MB. The editor opens files up to ${maximumBytes / MEGABYTE} MB.")

            else -> read(file)
        }
    }

    /**
     * Writes [contents] back to [document]'s file.
     *
     * Through a temporary file and a rename, so that a crash or a dead battery
     * part way through leaves the previous version intact. Saving directly onto
     * the file would make every save a moment where the user's source can be
     * destroyed.
     */
    suspend fun save(
        document: SourceDocument,
        contents: String,
    ): AppResult<Unit> = withContext(dispatchers.io) {
        val target = document.file
        val partial = File(target.parentFile, "${target.name}.saving")
        try {
            partial.writeBytes(document.encode(contents))
            if (!partial.renameTo(target)) {
                // Rename fails across filesystems and on some SAF-backed paths.
                // Copying is not atomic, but losing the save is worse than the
                // risk of an interrupted copy.
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            AppResult.Success(Unit)
        } catch (failure: Exception) {
            partial.delete()
            AppResult.Failure(
                AppError("${target.name} could not be saved: ${failure.message}", failure),
            )
        }
    }

    private fun read(file: File): AppResult<SourceDocument> = try {
        val bytes = file.readBytes()

        if (bytes.isBinary()) {
            failure("${file.name} is not a text file.")
        } else {
            val hasBom = bytes.size >= 3 &&
                bytes[0] == SourceDocument.UTF8_BOM[0] &&
                bytes[1] == SourceDocument.UTF8_BOM[1] &&
                bytes[2] == SourceDocument.UTF8_BOM[2]
            val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes

            // Strict UTF-8 first, and only fall back on failure. Decoding
            // leniently would replace every undecodable byte with U+FFFD, and
            // saving would then write that back -- silently corrupting a file
            // the user only meant to look at.
            val (text, charset) = try {
                decodeStrictly(body) to StandardCharsets.UTF_8
            } catch (_: CharacterCodingException) {
                String(body, StandardCharsets.ISO_8859_1) to StandardCharsets.ISO_8859_1
            }

            AppResult.Success(
                SourceDocument(
                    file = file,
                    text = text.replace("\r\n", "\n"),
                    encoding = charset,
                    lineEnding = LineEnding.of(text),
                    hasByteOrderMark = hasBom,
                ),
            )
        }
    } catch (failure: Exception) {
        AppResult.Failure(AppError("${file.name} could not be opened: ${failure.message}", failure))
    }

    private fun decodeStrictly(bytes: ByteArray): String = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString()

    /**
     * A NUL byte near the start is the same heuristic `grep` and `git` use.
     *
     * It is not exact, and does not need to be: the cost of being wrong is
     * refusing to open something odd, and the cost of not checking is rendering
     * a megabyte of binary as text and offering to save it back.
     */
    private fun ByteArray.isBinary(): Boolean =
        take(BINARY_SNIFF_BYTES).any { it == 0.toByte() }

    private fun failure(message: String) = AppResult.Failure(AppError(message))

    companion object {
        const val MEGABYTE = 1024 * 1024
        const val DEFAULT_MAXIMUM_BYTES = 8L * MEGABYTE
        private const val BINARY_SNIFF_BYTES = 8000
    }
}
