package com.osamu.aide.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * A diagnostic's tooltip goes when the diagnostic does.
 *
 * sora opens `EditorDiagnosticTooltipWindow` when the caret lands on a
 * diagnostic and updates it from selection changes. **Nothing tells it when the
 * diagnostics change underneath**, so fixing the error leaves its message on
 * screen, anchored where the old error was: seen reading "';' expected" across
 * two lines that no longer contained one, while the Problems tab had already
 * dropped to a single unrelated entry.
 *
 * The editor is really composed, because the fix lives in `CodeEditorView`'s
 * update block and the tooltip needs a laid-out widget to position against.
 */
class DiagnosticTooltipTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "tooltip-project")
    private val source = File(root, "Main.java")

    private val document = SourceDocument(
        file = source,
        text = "class Main {\n    void go() { int x = }\n}\n",
    )

    /** Column 24 of line 2, which is where the made-up error sits. */
    private val error = Diagnostic(
        file = File("Main.java"),
        line = 2,
        column = 24,
        severity = DiagnosticSeverity.ERROR,
        message = "';' expected",
    )

    @Test
    fun the_tooltip_goes_when_the_diagnostic_it_describes_does() {
        val controller = CodeEditorController()
        val diagnostics = mutableStateOf(listOf(error))
        compose.setContent {
            val shown by diagnostics
            CodeEditorView(
                document = document,
                openDocuments = listOf(document),
                languages = EditorLanguages(context),
                onTextChanged = {},
                controller = controller,
                diagnostics = shown,
                projectRoot = root,
            )
        }
        compose.waitUntil(TIMEOUT_MILLIS) { controller.text() == document.text }

        // **Opened by hand, deliberately.** sora opens this window from its own
        // hover and long-press handling, which a programmatic caret move does
        // not trigger -- `jumpTo` onto the error leaves it shut. What the bug is
        // about is not how the window opens but that nothing closes it, so the
        // test puts it in the state the bug leaves it in and asserts on the
        // half that was missing.
        val window = tooltip(controller)!!
        compose.runOnIdle {
            controller.jumpTo(line = 2, column = 24)
            window.show()
        }
        compose.waitUntil(TIMEOUT_MILLIS) { window.isShowing }
        assertTrue("the tooltip never opened, so this proves nothing", window.isShowing)

        // The user fixes it. The caret does not move.
        compose.runOnIdle { diagnostics.value = emptyList() }
        compose.waitForIdle()

        assertFalse("the tooltip outlived the diagnostic it describes", window.isShowing)
    }

    /**
     * Reaching sora's component through the controller's editor, which is the
     * only handle a test has on the widget the composable built.
     */
    private fun tooltip(controller: CodeEditorController): EditorDiagnosticTooltipWindow? =
        controller.attached()?.getComponent(EditorDiagnosticTooltipWindow::class.java)

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
