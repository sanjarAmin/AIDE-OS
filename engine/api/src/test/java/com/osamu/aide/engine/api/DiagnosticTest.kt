package com.osamu.aide.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosticTest {

    @Test
    fun `describe includes file line and column when located`() {
        val diagnostic = Diagnostic(
            severity = DiagnosticSeverity.ERROR,
            message = "cannot find symbol",
            file = File("src/Main.java"),
            line = 12,
            column = 5,
        )

        assertEquals("src/Main.java:12:5: error: cannot find symbol", diagnostic.describe())
    }

    @Test
    fun `describe omits the column when only a line is known`() {
        val diagnostic = Diagnostic(
            severity = DiagnosticSeverity.WARNING,
            message = "unchecked cast",
            file = File("Main.java"),
            line = 3,
        )

        assertEquals("Main.java:3: warning: unchecked cast", diagnostic.describe())
    }

    @Test
    fun `describe of an unlocated diagnostic is just severity and message`() {
        val diagnostic = Diagnostic(DiagnosticSeverity.ERROR, "keystore is unavailable")

        assertEquals("error: keystore is unavailable", diagnostic.describe())
        assertFalse(diagnostic.hasLocation)
    }

    @Test
    fun `the directory is kept, because for resources it is the whole point`() {
        // values/strings.xml and values-night/strings.xml are different files
        // with the same name, and which one failed is the entire question.
        val default = Diagnostic(
            severity = DiagnosticSeverity.ERROR,
            message = "duplicate value",
            file = File("res/values/strings.xml"),
            line = 4,
        )
        val night = default.copy(file = File("res/values-night/strings.xml"))

        assertTrue(default.describe() != night.describe())
    }

    @Test
    fun `a file with no line does not count as located`() {
        // aapt2 reports whole-file failures. Treating those as located would
        // send the editor to line 0, which does not exist.
        val diagnostic = Diagnostic(
            severity = DiagnosticSeverity.ERROR,
            message = "failed to open",
            file = File("values/strings.xml"),
        )

        assertFalse(diagnostic.hasLocation)
        assertEquals("values/strings.xml: error: failed to open", diagnostic.describe())
    }

    @Test
    fun `errors are separable from warnings`() {
        val diagnostics = listOf(
            Diagnostic(DiagnosticSeverity.WARNING, "deprecated"),
            Diagnostic(DiagnosticSeverity.ERROR, "broken"),
            Diagnostic(DiagnosticSeverity.INFO, "note"),
        )

        assertTrue(diagnostics.hasErrors)
        assertEquals(listOf("broken"), diagnostics.errors.map { it.message })
    }

    @Test
    fun `warnings alone are not errors`() {
        val diagnostics = listOf(Diagnostic(DiagnosticSeverity.WARNING, "deprecated"))

        assertFalse(diagnostics.hasErrors)
    }
}
