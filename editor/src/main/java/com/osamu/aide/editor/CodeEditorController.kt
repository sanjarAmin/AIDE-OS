package com.osamu.aide.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/** Where a search has got to, for the bar that drives it. */
data class SearchState(
    val query: String = "",
    val matches: Int = 0,
    /** 1-based position of the highlighted match, or 0 when there is none. */
    val current: Int = 0,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    /** Non-null when the pattern itself is bad -- an unclosed group, say. */
    val error: String? = null,
)

/**
 * A handle on the editor widget for the things around it: the symbol row, the
 * search bar, and jumping to the line a diagnostic names.
 *
 * These are all *imperative* -- "type this", "go there" -- and modelling them
 * as state would mean inventing a one-shot flag for each and clearing it again
 * afterwards. The widget is already the source of truth for the text and the
 * cursor; this only reaches it.
 *
 * Created with `remember` next to the editor and passed down. Every method is a
 * no-op while nothing is attached, so a symbol tapped during a tab switch does
 * nothing rather than crashing.
 */
@Stable
class CodeEditorController {

    private var editor: CodeEditor? = null

    /** Observable so a search bar recomposes when results land off-thread. */
    var search: SearchState by mutableStateOf(SearchState())
        private set

    internal fun attach(editor: CodeEditor) {
        if (this.editor === editor) return
        this.editor = editor
        // Matching runs on its own thread; this is how the count arrives.
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> refreshSearch() }
    }

    internal fun detach() {
        editor = null
        search = SearchState()
    }

    // -- Text -------------------------------------------------------------

    /** Types [text] at the cursor, replacing the selection. */
    fun insert(text: String) {
        editor?.commitText(text)
    }

    /** Deletes backwards, as the keyboard's own backspace does. */
    fun backspace() {
        editor?.deleteText()
    }

    fun undo() {
        editor?.takeIf { it.canUndo() }?.undo()
    }

    fun redo() {
        editor?.takeIf { it.canRedo() }?.redo()
    }

    // -- Navigation -------------------------------------------------------

    /**
     * Puts the cursor on a 1-based [line] and [column] and scrolls it into
     * view. Out-of-range positions are clamped: a diagnostic can name a line
     * past the end of a file the user has since edited, and that is not worth
     * an exception.
     */
    /**
     * Where the caret is, as a character index into the buffer.
     *
     * Null when no editor is attached. A language service works in offsets
     * because that is what a compiler's source positions are; the line/column
     * the editor thinks in is a rendering of the same thing.
     */
    fun cursorOffset(): Int? = editor?.cursor?.left

    fun jumpTo(line: Int, column: Int = 1) {
        val editor = editor ?: return
        val targetLine = (line - 1).coerceIn(0, editor.text.lineCount - 1)
        val targetColumn = (column - 1).coerceIn(0, editor.text.getColumnCount(targetLine))
        editor.setSelection(targetLine, targetColumn)
        editor.ensurePositionVisible(targetLine, targetColumn)
    }

    // -- Search -----------------------------------------------------------

    fun searchFor(
        query: String,
        caseSensitive: Boolean = search.caseSensitive,
        useRegex: Boolean = search.useRegex,
    ) {
        val searcher = editor?.searcher ?: return
        val base = SearchState(query = query, caseSensitive = caseSensitive, useRegex = useRegex)

        if (query.isEmpty()) {
            // An empty pattern throws rather than matching nothing, and an empty
            // box is what the bar looks like before anyone has typed.
            searcher.stopSearch()
            search = base
            return
        }

        val options = EditorSearcher.SearchOptions(
            if (useRegex) {
                EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            } else {
                EditorSearcher.SearchOptions.TYPE_NORMAL
            },
            !caseSensitive,
        )

        search = try {
            searcher.search(query, options)
            base
        } catch (failure: Exception) {
            // A half-typed regex is the normal case, not a bug: "(" is invalid
            // on the way to "(foo)".
            searcher.stopSearch()
            base.copy(error = failure.message ?: "That is not a valid pattern.")
        }
    }

    fun findNext() {
        editor?.searcher?.takeIf { it.hasQuery() }?.gotoNext()
        refreshSearch()
    }

    fun findPrevious() {
        editor?.searcher?.takeIf { it.hasQuery() }?.gotoPrevious()
        refreshSearch()
    }

    fun replaceCurrent(replacement: String) {
        val searcher = editor?.searcher?.takeIf { it.hasQuery() } ?: return
        // replaceCurrentMatch is a no-op unless a match is actually selected,
        // so step onto one first -- otherwise the first tap of Replace looks
        // like it did nothing.
        if (!searcher.isMatchedPositionSelected) searcher.gotoNext()
        searcher.replaceCurrentMatch(replacement)
        refreshSearch()
    }

    fun replaceAll(replacement: String) {
        editor?.searcher?.takeIf { it.hasQuery() }?.replaceAll(replacement)
        refreshSearch()
    }

    fun stopSearch() {
        editor?.searcher?.stopSearch()
        search = SearchState()
    }

    private fun refreshSearch() {
        val searcher = editor?.searcher ?: return
        search = search.copy(
            matches = runCatching { searcher.matchedPositionCount }.getOrDefault(0),
            // The searcher reports -1 when nothing is highlighted; the bar
            // counts from one.
            current = runCatching { searcher.currentMatchedPositionIndex + 1 }.getOrDefault(0),
            error = null,
        )
    }
}
