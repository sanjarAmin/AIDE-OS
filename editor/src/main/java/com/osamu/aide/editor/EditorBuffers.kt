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

    /** The reload token each buffer was built from; see [bufferFor]. */
    private val tokens = mutableMapOf<String, Int>()

    /**
     * The live buffer for [document], rebuilt when [reloadToken] changes.
     *
     * **The cache is what makes a tab switch keep its undo history**, and it is
     * also what made a file changed on disk invisible: a document re-read with
     * new text returned the Content built from the old text, so the assistant
     * could rewrite a file the user was looking at and the screen would not
     * move. The token is the caller saying "this is genuinely new content",
     * which only a reload does -- typing does not.
     */
    fun bufferFor(document: SourceDocument, reloadToken: Int = 0): Content {
        val key = document.file.absolutePath
        if (tokens[key] != reloadToken) {
            buffers.remove(key)
            tokens[key] = reloadToken
        }
        return buffers.getOrPut(key) { Content(document.text) }
    }

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
        tokens.keys.retainAll(open)
    }

    fun isOpen(file: File): Boolean = file.absolutePath in buffers

    fun clear() {
        buffers.clear()
        tokens.clear()
    }
}
