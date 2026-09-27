package com.osamu.aide.ui.workspace

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What "fix this" actually asks for.
 *
 * A JVM test rather than an instrumented one: the message is a string, and the
 * property worth pinning is that its path is in the form the assistant's own
 * tools accept. `ProjectFiles.resolve` refuses absolute paths by design, so an
 * absolute path here would spend the first tool call on a refusal -- a failure
 * that costs a round trip and shows up only as the assistant seeming slow and
 * confused.
 */
class FixRequestTest {

    private val root = File("/data/projects/Demo")

    private fun diagnostic(
        file: File? = File(root, "app/src/main/java/Main.java"),
        line: Int = 12,
        column: Int = 5,
        message: String = "cannot find symbol: class Widget",
        severity: DiagnosticSeverity = DiagnosticSeverity.ERROR,
    ) = Diagnostic(severity, message, file, line, column)

    @Test
    fun the_path_is_relative_to_the_project() {
        val request = fixRequest(diagnostic(), root)

        assertTrue(request, "app/src/main/java/Main.java:12:5" in request)
        assertFalse("an absolute path would be refused by the tools:\n$request", "/data/" in request)
    }

    @Test
    fun the_error_text_is_carried_verbatim() {
        assertTrue("cannot find symbol: class Widget" in fixRequest(diagnostic(), root))
    }

    /** A warning is not an error, and saying so changes what gets suggested. */
    @Test
    fun the_severity_is_named() {
        assertTrue(
            "warning" in fixRequest(diagnostic(severity = DiagnosticSeverity.WARNING), root),
        )
        assertTrue("error" in fixRequest(diagnostic(), root))
    }

    /**
     * A build can fail with no file at all -- a signing failure, say. The
     * message must still be a sentence rather than a dangling colon.
     */
    @Test
    fun a_diagnostic_with_no_file_still_produces_a_usable_message() {
        val request = fixRequest(
            diagnostic(file = null, line = Diagnostic.UNKNOWN, column = Diagnostic.UNKNOWN),
            root,
        )

        assertTrue(request, "cannot find symbol" in request)
        assertFalse("an empty location leaked in:\n$request", ":0" in request)
    }

    @Test
    fun a_line_without_a_column_is_not_padded_with_a_zero() {
        val request = fixRequest(diagnostic(column = Diagnostic.UNKNOWN), root)

        assertTrue(request, "Main.java:12\n" in request)
        assertFalse(request, "Main.java:12:0" in request)
    }

    /**
     * A file outside the project falls back to its name.
     *
     * `relativeTo` would produce `../../something`, which the tools refuse for
     * the same reason as an absolute path -- and a bare name at least gives the
     * assistant something to search for.
     */
    @Test
    fun a_file_outside_the_project_degrades_to_its_name() {
        val request = fixRequest(diagnostic(file = File("/tmp/Stray.java")), root)

        assertTrue(request, "Stray.java" in request)
        assertFalse("an escaping path would be refused by the tools:\n$request", ".." in request)
    }

    /** It has to ask for the cause, not only the edit. */
    @Test
    fun the_assistant_is_asked_to_explain_as_well_as_fix() {
        val request = fixRequest(diagnostic(), root)

        assertTrue(request, "causing it" in request)
        assertEquals(1, request.lines().count { it.startsWith("Read the file") })
    }
}
