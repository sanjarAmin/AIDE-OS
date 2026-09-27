package com.osamu.aide.lsp.node

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parsing half of [NodeLanguageService], without a device.
 *
 * The fixtures are **captured output**, not invented: each was produced by
 * running `node --check` on the source in its comment. A parser tested against
 * output somebody imagined is a parser tested against nothing.
 */
class NodeSyntaxCheckTest {

    private val file = File("index.js")

    /**
     * ```
     * const a = 1;
     * function f( {
     *   return a;
     * }
     * ```
     */
    private val unexpectedIdentifier = """
        /data/user/0/com.osamu.aide/cache/check-index.js:3
          return a;
                 ^

        SyntaxError: Unexpected identifier 'a'
            at wrapSafe (node:internal/modules/cjs/loader:1866:18)
            at checkSyntax (node:internal/main/check_syntax:88:3)

        Node.js v24.18.0
    """.trimIndent()

    @Test
    fun it_reports_the_line_the_column_and_the_message() {
        val diagnostics = NodeSyntaxCheck.parse(unexpectedIdentifier, file)

        assertEquals(1, diagnostics.size)
        val only = diagnostics.single()
        assertEquals(DiagnosticSeverity.ERROR, only.severity)
        assertEquals("SyntaxError: Unexpected identifier 'a'", only.message)
        assertEquals(3, only.line)
        // The caret sits under `a`, which is column 10 counting from 1.
        assertEquals(10, only.column)
    }

    /**
     * The file reported is the **user's**, not the scratch copy node saw.
     *
     * The check runs against a copy of the buffer, so the path in the output
     * names a file under the app's cache. Reporting that would put a diagnostic
     * on a file the editor has no tab for.
     */
    @Test
    fun it_reports_the_users_file_and_not_the_one_node_was_given() {
        val only = NodeSyntaxCheck.parse(unexpectedIdentifier, file).single()

        assertEquals(file, only.file)
        assertTrue("the scratch path leaked into the message", "check-index.js" !in only.message)
    }

    /**
     * Redeclaration is an early error, so `--check` catches it.
     *
     * Worth pinning because it is the one case where this service says
     * something a plain parser would not: `let a` twice is syntactically fine
     * and rejected before execution.
     */
    @Test
    fun it_reports_a_redeclaration() {
        val output = """
            /data/user/0/com.osamu.aide/cache/check-index.js:2
            let a = 2;
                ^

            SyntaxError: Identifier 'a' has already been declared
                at wrapSafe (node:internal/modules/cjs/loader:1866:18)

            Node.js v24.18.0
        """.trimIndent()

        val only = NodeSyntaxCheck.parse(output, file).single()
        assertEquals(2, only.line)
        assertEquals(5, only.column)
        assertTrue(only.message.contains("already been declared"))
    }

    /** A file that is fine produces no output at all, and that is not an error. */
    @Test
    fun a_clean_file_produces_nothing() {
        assertEquals(emptyList<Diagnostic>(), NodeSyntaxCheck.parse("", file))
    }

    /**
     * A stack frame is not a diagnostic.
     *
     * Node's frames end in `:line:column` and would satisfy a header pattern
     * that only looked for a trailing number. The one this uses is anchored to
     * end of line, and the message is matched by error name rather than by
     * position, so neither half can be fooled by the frames below the error.
     */
    @Test
    fun node_s_own_stack_frames_are_not_mistaken_for_the_error() {
        val output = """
            /data/user/0/com.osamu.aide/cache/check-index.js:1
            const = 1;
                  ^

            SyntaxError: Unexpected token '='
                at wrapSafe (node:internal/modules/cjs/loader:1866:18)
                at checkSyntax (node:internal/main/check_syntax:88:3)
                at node:internal/main/check_syntax:44:3

            Node.js v24.18.0
        """.trimIndent()

        val diagnostics = NodeSyntaxCheck.parse(output, file)
        assertEquals("a stack frame was read as a second error", 1, diagnostics.size)
        assertEquals(1, diagnostics.single().line)
        assertEquals("SyntaxError: Unexpected token '='", diagnostics.single().message)
    }

    /**
     * Output in a shape this has never seen is answered with silence.
     *
     * Every method of a LanguageService is asked on a keystroke against a
     * buffer mid-edit; throwing on an unrecognised shape would turn a changed
     * node version into an editor that crashes as you type.
     */
    @Test
    fun output_it_cannot_read_is_not_an_exception() {
        assertEquals(
            emptyList<Diagnostic>(),
            NodeSyntaxCheck.parse("something entirely unlike node's output", file),
        )
        assertEquals(
            emptyList<Diagnostic>(),
            NodeSyntaxCheck.parse("/path/index.js:7\nno message follows this\n", file),
        )
    }
}
