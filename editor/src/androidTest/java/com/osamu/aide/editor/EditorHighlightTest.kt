package com.osamu.aide.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M1's acceptance criterion, as far as an emulator can settle it: open a
 * 5,000-line Java file and check the highlighting is right.
 *
 * This is the level [TreeSitterQueryTest] cannot reach. That one proves the
 * queries compile and share vocabulary with the theme; this one proves the
 * result actually arrives as coloured spans on the lines it should. Between a
 * compiling query and a correctly coloured file sit the analyzer, the theme
 * builder and the widget, and a mistake in any of them looks like plain text.
 *
 * The other half of the criterion -- 60fps scrolling -- is deliberately **not**
 * asserted here. Frame timing on an emulator running on a desktop says nothing
 * about a phone, and a number that passes everywhere is worse than no number.
 * It belongs to the manual device matrix in docs/PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class EditorHighlightTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var editor: CodeEditor
    private lateinit var languages: EditorLanguages

    /** Lines whose contents this test knows, so it can assert on them by name. */
    private object Line {
        const val PACKAGE = 0
        const val COMMENT = 2
        const val CLASS_DECLARATION = 3
        const val STRING_FIELD = 4
    }

    private val source: String = buildString {
        appendLine("package com.example.demo;")
        appendLine()
        appendLine("// A generated file, large enough to be worth measuring.")
        appendLine("public class Big {")
        appendLine("    private static final String GREETING = \"hello\";")
        // Enough repetitions to clear 5,000 lines.
        repeat(1000) { index ->
            appendLine()
            appendLine("    /** Method number $index. */")
            appendLine("    public int method$index(int value) {")
            appendLine("        return value + $index;")
            appendLine("    }")
        }
        appendLine("}")
    }

    @Before
    fun setUp() {
        assertTrue("tree-sitter's native core did not load", TreeSitterRuntime.isAvailable)
        val context = instrumentation.targetContext
        languages = EditorLanguages(context)
        instrumentation.runOnMainSync {
            editor = CodeEditor(context)
            editor.setEditorLanguage(languages.languageFor(File("Big.java")))
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { editor.release() }
    }

    /**
     * Sets the text and waits for the first full analysis.
     *
     * Analysis is asynchronous, so there is no way to observe it but to wait for
     * spans to appear. Polling rather than a fixed sleep, because a fixed sleep
     * long enough to be reliable is long enough to make the suite tedious.
     */
    private fun analyse(text: String): Long {
        val startedAt = System.nanoTime()
        instrumentation.runOnMainSync { editor.setText(text) }

        val deadline = System.currentTimeMillis() + ANALYSIS_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (spansOn(Line.CLASS_DECLARATION).isNotEmpty()) {
                return (System.nanoTime() - startedAt) / 1_000_000
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("no spans after ${ANALYSIS_TIMEOUT_MILLIS}ms")
    }

    private fun spansOn(line: Int): List<Span> {
        var spans: List<Span> = emptyList()
        instrumentation.runOnMainSync {
            val reader = editor.styles?.spans?.read() ?: return@runOnMainSync
            spans = runCatching { reader.getSpansOnLine(line) }.getOrDefault(emptyList())
        }
        return spans
    }

    private fun colorsOn(line: Int): Set<Int> =
        spansOn(line).map { it.foregroundColorId }.toSet()

    @Test
    fun a_five_thousand_line_java_file_is_highlighted() {
        assertTrue("the fixture is only ${source.lines().size} lines", source.lines().size > 5000)

        val millis = analyse(source)

        // The whole file loaded, not a prefix of it.
        var lineCount = 0
        instrumentation.runOnMainSync { lineCount = editor.text.lineCount }
        assertEquals(source.lines().size, lineCount)

        assertTrue(
            "the class declaration is not coloured: ${colorsOn(Line.CLASS_DECLARATION)}",
            EditorColorScheme.KEYWORD in colorsOn(Line.CLASS_DECLARATION),
        )
        assertTrue(
            "a comment is not coloured as one: ${colorsOn(Line.COMMENT)}",
            EditorColorScheme.COMMENT in colorsOn(Line.COMMENT),
        )
        assertTrue(
            "a string literal is not coloured: ${colorsOn(Line.STRING_FIELD)}",
            EditorColorScheme.LITERAL in colorsOn(Line.STRING_FIELD),
        )
        assertTrue(
            "the package statement is not coloured: ${colorsOn(Line.PACKAGE)}",
            EditorColorScheme.KEYWORD in colorsOn(Line.PACKAGE),
        )

        assertTrue(
            "highlighting a 5,000-line file took ${millis}ms",
            millis < ANALYSIS_BUDGET_MILLIS,
        )
    }

    @Test
    fun highlighting_reaches_the_far_end_of_the_file() {
        // A parser that gave up part way through, or an analyzer that only
        // styles the visible window, would pass every assertion above.
        analyse(source)

        val lastMethod = source.lines().indexOfLast { it.contains("public int method999") }
        assertTrue("fixture changed", lastMethod > 4000)

        assertTrue(
            "line $lastMethod near the end of the file is unstyled",
            EditorColorScheme.KEYWORD in colorsOn(lastMethod),
        )
    }

    @Test
    fun a_second_file_of_the_same_language_still_highlights() {
        // The editor destroys the outgoing language on every setEditorLanguage,
        // and destroying a tree-sitter language closes its spec. Anything that
        // shares one spec between two files therefore works exactly once, and
        // fails on the file after it with "spec is closed" -- which reads as a
        // corrupt install rather than as what it is.
        analyse(source)

        instrumentation.runOnMainSync {
            editor.setEditorLanguage(languages.languageFor(File("Other.java")))
        }
        analyse(source)

        assertTrue(
            "the second Java file opened is unstyled: ${colorsOn(Line.CLASS_DECLARATION)}",
            EditorColorScheme.KEYWORD in colorsOn(Line.CLASS_DECLARATION),
        )
    }

    @Test
    fun an_unknown_file_type_opens_as_plain_text_rather_than_failing() {
        instrumentation.runOnMainSync {
            editor.setEditorLanguage(languages.languageFor(File("notes.unknown")))
            editor.setText("this is not a language we know\n")
        }

        var text = ""
        instrumentation.runOnMainSync { text = editor.text.toString() }
        assertEquals("this is not a language we know\n", text)
    }

    private companion object {
        const val ANALYSIS_TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 25L

        /**
         * Generous on purpose. This runs on an emulator, so the number means
         * "the analyzer is not doing something pathological", not "this is fast
         * on a phone" -- which only the device matrix can say.
         */
        const val ANALYSIS_BUDGET_MILLIS = 10_000L
    }
}
