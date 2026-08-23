package com.osamu.aide.editor

import io.github.rosemoe.sora.text.Content
import java.io.File

/**
 * One text buffer per open file, kept alive across tab switches.
 *
 * A [Content] is not just the characters: it carries the undo stack and the
 * cursor. `CodeEditor.setText` builds a fresh one unless it is handed a
 * [Content] to reuse, so switching tabs without this would silently discard
 * both -- you would come back to a file you had been editing to find the cursor
 * at the top and Undo empty, which reads as data loss even though nothing was
 * lost.
 *
 * Not thread-safe, and does not need to be: it is touched only from the
 * composition, which is the main thread.
 */
class EditorBuffers {

    private val buffers = mutableMapOf<String, Content>()

    fun bufferFor(document: SourceDocument): Content =
        buffers.getOrPut(document.file.absolutePath) { Content(document.text) }

    /**
     * Drops the buffers of files that are no longer open.
     *
     * Called with the whole open set rather than told about each close, because
     * the composition sees the list and not the event -- and a buffer that
     * outlives its tab is a leak of the entire file's text plus its history.
     */
    fun retainOnly(documents: List<SourceDocument>) {
        val open = documents.mapTo(mutableSetOf()) { it.file.absolutePath }
        buffers.keys.retainAll(open)
    }

    fun isOpen(file: File): Boolean = file.absolutePath in buffers

    fun clear() = buffers.clear()
}
