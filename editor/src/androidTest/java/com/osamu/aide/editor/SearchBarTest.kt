package com.osamu.aide.editor

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The find bar takes focus as it appears, and what is typed next lands in it.
 *
 * It is opened by a button in the toolbar, so the tap that opens it is not a
 * tap into it. Without focus the user types and nothing happens — which is
 * exactly what happened when this screen was driven by hand, and is invisible
 * to a test that sets the query through the controller instead of typing.
 */
class SearchBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * A real editor, because a detached controller drops every query.
     *
     * `searchFor` returns early without one — correct, and it means a test that
     * only renders the bar would assert against a controller that can never
     * record anything. The first version of this test did exactly that and
     * failed for the wrong reason.
     */
    private fun controllerOverText(text: String): CodeEditorController {
        val controller = CodeEditorController()
        instrumentation.runOnMainSync {
            val editor = CodeEditor(instrumentation.targetContext)
            editor.setText(text)
            controller.attach(editor)
        }
        return controller
    }

    @Test
    fun the_find_field_is_focused_when_the_bar_opens() {
        // Built before composing: `runOnMainSync` cannot be called from the
        // main thread, and `setContent`'s body already is one.
        val controller = controllerOverText("nothing here")
        compose.setContent { SearchBar(controller = controller, onDismiss = {}) }

        compose.onNodeWithText("Find").assertIsFocused()
    }

    @Test
    fun typing_straight_after_opening_reaches_the_query() {
        val controller = controllerOverText("one needle, two needle\nthree needle\n")
        compose.setContent { SearchBar(controller = controller, onDismiss = {}) }

        compose.onNodeWithText("Find").performTextInput("needle")

        assertEquals("needle", controller.search.query)
        // Matching runs on its own thread and reports back through an event, so
        // the count arrives after the keystroke rather than with it.
        compose.waitUntil(TIMEOUT_MILLIS) { controller.search.matches > 0 }
        assertTrue(
            "the query reached the searcher but found nothing: ${controller.search}",
            controller.search.matches >= 3,
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
