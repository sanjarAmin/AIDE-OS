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

    /**
     * The grammar this project builds itself parses a real `.js` buffer.
     *
     * [TreeSitterQueryTest] proves the library loads and the query compiles;
     * neither says the parser produces a tree. A grammar whose ABI the runtime
     * rejects still loads, still answers `TSLanguage.create`, and then colours
     * nothing -- which is indistinguishable from the plain text this replaced.
     * `tools/treesitter/FINDINGS.md`.
     */
    @Test
    fun a_javascript_file_is_highlighted_by_the_grammar_we_build() {
        val script = buildString {
            appendLine("// A Node entry point.")
            appendLine("const greeting = 'hello';")
            appendLine("function greet(name) {")
            appendLine("    return greeting + name;")
            appendLine("}")
        }
        // Line 1 is `const greeting = ...`, which carries both a keyword and a
        // string: two different capture families from one line.
        analyseAs("index.js", script, line = 1)

        assertTrue(
            "`const` is not coloured as a keyword: ${colorsOn(1)}",
            EditorColorScheme.KEYWORD in colorsOn(1),
        )
        assertTrue(
            "a string literal is not coloured: ${colorsOn(1)}",
            EditorColorScheme.LITERAL in colorsOn(1),
        )
        assertTrue(
            "a comment is not coloured as one: ${colorsOn(0)}",
            EditorColorScheme.COMMENT in colorsOn(0),
        )
    }

    /**
     * A `.jsx` tag is coloured, which is the whole reason it is its own entry.
     *
     * The grammar is JavaScript's and parses JSX already; what `.jsx` needed
     * was upstream's supplementary query. Claiming the extension without it
     * would leave every tag uncoloured -- indistinguishable from the plain text
     * this replaced, and exactly the failure `TreeSitterQueryTest` cannot see,
     * because a query that compiles can still say nothing about tags.
     */
    @Test
    fun a_jsx_tag_is_highlighted_and_a_plain_js_file_still_is_too() {
        val component = buildString {
            appendLine("// A component.")
            appendLine("const hello = 'hi';")
            appendLine("function App() {")
            appendLine("    return <section className=\"box\">{hello}</section>;")
            appendLine("}")
        }
        val tagLine = 3
        analyseAs("App.jsx", component, line = tagLine)

        // `section` is captured as @tag, which the theme colours as an
        // identifier; `"box"` is a string. Both on one line means the JSX half
        // of the query ran, not only the JavaScript half.
        assertTrue(
            "a JSX tag is not coloured: ${colorsOn(tagLine)}",
            EditorColorScheme.IDENTIFIER_NAME in colorsOn(tagLine),
        )
        assertTrue(
            "the string in a JSX attribute is not coloured: ${colorsOn(tagLine)}",
            EditorColorScheme.LITERAL in colorsOn(tagLine),
        )
        assertTrue(
            "a comment is not coloured as one: ${colorsOn(0)}",
            EditorColorScheme.COMMENT in colorsOn(0),
        )
    }

    /**
     * Opens [text] as [fileName] and waits until [line] carries more than one
     * colour -- one colour is what an unhighlighted line has.
     *
     * Throws on timeout rather than returning, as [analyse] does: otherwise a
     * grammar that never produced a span fails as "`return` is not coloured",
     * which reads as a wrong colour rather than as no highlighting at all.
     */
    private fun analyseAs(fileName: String, text: String, line: Int) {
        instrumentation.runOnMainSync {
            editor.setEditorLanguage(languages.languageFor(File(fileName)))
            editor.setText(text)
        }
        val deadline = System.currentTimeMillis() + ANALYSIS_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (colorsOn(line).size > 1) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError(
            "$fileName: line $line still has one colour after ${ANALYSIS_TIMEOUT_MILLIS}ms: ${colorsOn(line)}",
        )
    }

    /**
     * The JNI template's own file, which opened entirely uncoloured: C and C++
     * had no grammar, so they fell through to plain text with nothing to say so.
     */
    @Test
    fun a_cpp_file_is_highlighted() {
        val native = buildString {
            appendLine("#include <jni.h>")                                    // 0
            appendLine("// The name is the contract.")                        // 1
            appendLine("extern \"C\" JNIEXPORT jstring JNICALL")                // 2
            appendLine("Java_com_example_c_MainActivity_describe(JNIEnv *env) {") // 3
            appendLine("    std::string message = \"C++ squared \";")          // 4
            appendLine("    int squared = square(3);")                         // 5
            appendLine("    return env->NewStringUTF(message.c_str());")       // 6
            appendLine("}")
        }
        analyseAs("native.cpp", native, line = 4)

        assertTrue(
            "`#include` is not coloured as a keyword: ${colorsOn(0)}",
            EditorColorScheme.KEYWORD in colorsOn(0),
        )
        assertTrue(
            "a comment is not coloured as one: ${colorsOn(1)}",
            EditorColorScheme.COMMENT in colorsOn(1),
        )
        // The `extern "C"` string only exists in C++'s grammar -- C's reads it
        // as an error -- and `std::string` is a C++ type, so these two lines
        // prove the C++ half of the concatenated query ran and not only C's.
        assertTrue(
            "`extern \"C\"` is not coloured: ${colorsOn(2)}",
            EditorColorScheme.LITERAL in colorsOn(2),
        )
        assertTrue(
            "`std::string` is not coloured as a type: ${colorsOn(4)}",
            EditorColorScheme.IDENTIFIER_NAME in colorsOn(4),
        )
        assertTrue(
            "a string literal is not coloured: ${colorsOn(4)}",
            EditorColorScheme.LITERAL in colorsOn(4),
        )
        // The query's first pattern colours every identifier as a variable,
        // and sora keeps whichever capture of a node it meets first -- so a
        // call is only coloured as one if the more specific pattern wins.
        assertTrue(
            "a function call is not coloured as one: ${colorsOn(5)}",
            EditorColorScheme.FUNCTION_NAME in colorsOn(5),
        )
        assertTrue(
            "`return` is not coloured as a keyword: ${colorsOn(6)}",
            EditorColorScheme.KEYWORD in colorsOn(6),
        )
    }

    @Test
    fun a_c_file_is_highlighted_by_the_c_grammar() {
        val program = buildString {
            appendLine("/* A C file. */")                         // 0
            appendLine("int main(void) {")                        // 1
            // `new` is an ordinary identifier in C and a keyword in C++: the
            // reason `.c` has a grammar of its own.
            appendLine("    int new = 1;")                        // 2
            appendLine("    printf(\"%d\\n\", new);")             // 3
            appendLine("    return 0;")                           // 4
            appendLine("}")
        }
        analyseAs("main.c", program, line = 3)

        assertEquals(EditorLanguage.C, EditorLanguage.of(File("main.c")))

        assertTrue(
            "a comment is not coloured as one: ${colorsOn(0)}",
            EditorColorScheme.COMMENT in colorsOn(0),
        )
        assertTrue(
            "`int` is not coloured as a type: ${colorsOn(1)}",
            EditorColorScheme.IDENTIFIER_NAME in colorsOn(1),
        )
        assertTrue(
            "a string literal is not coloured: ${colorsOn(3)}",
            EditorColorScheme.LITERAL in colorsOn(3),
        )
        assertTrue(
            "`return` is not coloured as a keyword: ${colorsOn(4)}",
            EditorColorScheme.KEYWORD in colorsOn(4),
        )
    }

    /** A header is claimed by C++, and a class in one is coloured as C++. */
    @Test
    fun a_header_is_read_as_cpp() {
        val header = buildString {
            appendLine("#pragma once")               // 0
            appendLine("namespace demo {")           // 1
            appendLine("class Greeter {")            // 2
            appendLine("};")
            appendLine("}")
        }
        analyseAs("greeter.h", header, line = 2)

        assertEquals(EditorLanguage.CPP, EditorLanguage.of(File("greeter.h")))
        assertTrue(
            "`class` in a header is not coloured as a keyword: ${colorsOn(2)}",
            EditorColorScheme.KEYWORD in colorsOn(2),
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
