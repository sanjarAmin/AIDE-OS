package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fixtures here are ECJ 3.38.0's real `-Xemacs` output, captured by running
 * it rather than written from memory.
 */
class EcjDiagnosticsTest {

    private val root = File("/data/user/0/com.osamu.aide/files/projects/demo")

    @Test
    fun `parses one diagnostic per problem, ignoring the echoed source`() {
        val output = """
            $root/src/main/java/demo/Broken.java:5: error: The method missing(int) is undefined for the type Broken
            	return missing(1);
            	       ^^^^^^^
            $root/src/main/java/demo/Broken.java:9: error: Type mismatch: cannot convert from String to int
            	int x = "not an int";
            	        ^^^^^^^^^^^^
            3 problems (3 errors)
        """.trimIndent()

        val diagnostics = EcjDiagnostics.parse(output, root)

        assertEquals(2, diagnostics.size)
        assertEquals(
            "The method missing(int) is undefined for the type Broken",
            diagnostics[0].message,
        )
        assertEquals(File("src/main/java/demo/Broken.java"), diagnostics[0].file)
        assertEquals(5, diagnostics[0].line)
        assertEquals(9, diagnostics[1].line)
        assertTrue(diagnostics.hasErrors)
    }

    @Test
    fun `no column is reported, because ECJ's caret cannot supply a real one`() {
        // ECJ strips the source line's indentation before echoing it, so the
        // caret's offset is not the column in the file. Reporting it would send
        // the editor to the wrong place, which is worse than sending it to the
        // start of the line.
        val output = "$root/src/main/java/demo/Broken.java:10: error: Type mismatch"

        assertEquals(Diagnostic.UNKNOWN, EcjDiagnostics.parse(output, root).single().column)
    }

    @Test
    fun `warnings are distinguished from errors`() {
        val output = "$root/src/main/java/demo/Old.java:3: warning: The type X is deprecated"

        val diagnostics = EcjDiagnostics.parse(output, root)

        assertEquals(DiagnosticSeverity.WARNING, diagnostics.single().severity)
        assertFalse(diagnostics.hasErrors)
    }

    @Test
    fun `generated sources keep their absolute path`() {
        // R.java lives in the build workspace, not the project. Rewriting it to
        // look project-relative would present a generated file as one the user
        // wrote and could open.
        val output = "/data/user/0/com.osamu.aide/cache/build/generated/java/demo/R.java:6: error: duplicate field"

        val diagnostic = EcjDiagnostics.parse(output, root).single()

        assertTrue(
            "expected an absolute path, got ${diagnostic.file}",
            diagnostic.file!!.isAbsolute,
        )
    }

    @Test
    fun `a clean compile produces no diagnostics`() {
        assertTrue(EcjDiagnostics.parse("", root).isEmpty())
    }

    @Test
    fun `the summary line is not mistaken for a problem`() {
        assertTrue(EcjDiagnostics.parse("3 problems (3 errors)", root).isEmpty())
    }
}
