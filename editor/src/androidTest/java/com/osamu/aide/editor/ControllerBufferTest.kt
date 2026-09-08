package com.osamu.aide.editor

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * [CodeEditorController.text] and [CodeEditorController.cursorOffset] agree.
 *
 * Anything that slices the file around the cursor needs both, and taking them
 * from different places is what broke inline completion: the offset came from
 * the widget while the text came from [SourceDocument.text], which is the
 * **last saved** copy -- an edit is reported through `onTextChanged`, lands in
 * the view model's pending map, and never reaches the document. Slicing the
 * saved text at a live offset produced a context describing a file the user
 * was not looking at, and the assistant, shown the line before the one being
 * typed, correctly said there was nothing to add.
 *
 * So the editor is really composed here rather than constructed by hand. A
 * bare `CodeEditor` accepts `setText` but throws out of `commitText`, because
 * typing updates a line layout that only exists once the widget has been
 * measured -- and typing is the whole subject.
 */
class ControllerBufferTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Built once: each instance loads tree-sitter grammars. */
    private val languages by lazy { EditorLanguages(context) }

    /** What the view model saw, kept the way the view model keeps it. */
    private class Edits {
        var latest: String? = null
    }

    /**
     * Shows [text] in a real editor and returns the controller on it, plus the
     * document and the edit log, which stand in for the view model.
     */
    private fun editing(text: String): Triple<CodeEditorController, SourceDocument, Edits> {
        val document = SourceDocument(file = File("/tmp/Buffer.java"), text = text)
        val edits = Edits()
        val controller = CodeEditorController()
        compose.setContent {
            CodeEditorView(
                document = document,
                openDocuments = listOf(document),
                languages = languages,
                onTextChanged = { edits.latest = it },
                controller = controller,
            )
        }
        compose.waitUntil(TIMEOUT_MILLIS) { controller.text() == text }
        return Triple(controller, document, edits)
    }

    @Test
    fun the_buffer_includes_an_edit_the_widget_has_and_nothing_has_saved() {
        val (controller, document, edits) = editing("one\ntwo\n")

        compose.runOnIdle { controller.insert("three") }
        compose.waitUntil(TIMEOUT_MILLIS) { edits.latest?.contains("three") == true }

        assertTrue(
            "the edit is not in the buffer: ${controller.text()}",
            controller.text()!!.contains("three"),
        )
        // The half that made this worth a test: the document is a whole edit
        // behind, and always will be until a save. Reading text from here and
        // the cursor from the controller is the bug.
        assertFalse(
            "the document was updated, so this test no longer proves anything",
            document.text.contains("three"),
        )
    }

    /**
     * And the offset is inside it. A cursor past the end of the text it is
     * used to slice is the shape of the bug, not a hypothetical: it happens
     * whenever the two come from sources that update at different times.
     */
    @Test
    fun the_cursor_is_an_offset_into_the_text_the_controller_reports() {
        val (controller, document, _) = editing("alpha\nbeta\n")

        // Typed at the end of the file, which is where the offset and the
        // saved copy part company most plainly.
        compose.runOnIdle {
            controller.jumpTo(line = 3)
            controller.insert("gamma")
        }
        compose.waitUntil(TIMEOUT_MILLIS) { controller.text()!!.contains("gamma") }

        val buffer = controller.text()!!
        val cursor = controller.cursorOffset()!!
        assertTrue("cursor $cursor is outside a buffer of ${buffer.length}", cursor <= buffer.length)
        // The split either side of the cursor reassembles the buffer, which is
        // what the completion context is built from.
        assertEquals(buffer, buffer.take(cursor) + buffer.drop(cursor))
        // Against the document it would not. The cursor is past the end of the
        // saved text, so `take(cursor)` silently returns the whole of it and
        // `drop(cursor)` returns nothing: a prefix missing the line being typed
        // and an empty suffix, which is the context inline completion was
        // sending.
        assertTrue(
            "cursor $cursor is inside the saved copy too, so the two cannot disagree",
            cursor > document.text.length,
        )
    }

    /** Detached, both answer null rather than a stale or invented value. */
    @Test
    fun a_detached_controller_reports_nothing() {
        val controller = CodeEditorController()

        assertNull(controller.text())
        assertNull(controller.cursorOffset())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
