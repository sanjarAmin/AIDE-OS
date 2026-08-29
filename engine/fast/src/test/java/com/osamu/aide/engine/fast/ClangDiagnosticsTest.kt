package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ClangDiagnosticsTest {

    private val root = File("/data/user/0/pkg/projects/demo")

    @Test
    fun `a located error becomes a tappable diagnostic`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "${root.path}/src/main/cpp/hello.c:4:12: error: use of undeclared identifier 'x'",
            root,
        )!!

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("src/main/cpp/hello.c", diagnostic.file?.path)
        assertEquals(4, diagnostic.line)
        assertEquals(12, diagnostic.column)
        assertEquals("use of undeclared identifier 'x'", diagnostic.message)
    }

    /**
     * The reason this parser exists rather than reusing aapt2's.
     *
     * clang prefixes its own failures with the driver's name, and aapt2's
     * pattern would read `clang-21` as a filename -- putting a tappable link to
     * a file that does not exist in front of the user, for the one error class
     * they can do nothing about.
     */
    @Test
    fun `the driver's own failure is not mistaken for a file`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "clang-21: error: unable to execute command: Program could not be executed",
            root,
        )!!

        assertNull("the driver's name was parsed as a file", diagnostic.file)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(
            "clang-21: unable to execute command: Program could not be executed",
            diagnostic.message,
        )
    }

    @Test
    fun `the linker's own failure is not mistaken for a file either`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "ld.lld: error: undefined symbol: aide_missing",
            root,
        )!!

        assertNull(diagnostic.file)
        assertEquals("ld.lld: undefined symbol: aide_missing", diagnostic.message)
    }

    @Test
    fun `fatal errors are errors`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "${root.path}/src/main/cpp/a.cpp:2:10: fatal error: 'string' file not found",
            root,
        )!!

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(2, diagnostic.line)
    }

    /**
     * Notes are kept. For a template or overload error clang puts the useful
     * half in the note -- which candidate it rejected, and why.
     */
    @Test
    fun `notes are kept as information`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "${root.path}/src/main/cpp/a.cpp:9:5: note: candidate function not viable",
            root,
        )!!

        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity)
    }

    @Test
    fun `warnings keep their severity`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "${root.path}/src/main/cpp/a.c:3:9: warning: unused variable 'n'",
            root,
        )!!

        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
    }

    /** Blank spacing is not a message. */
    @Test
    fun `blank lines are dropped`() {
        assertNull(ClangDiagnostics.parseLine("   ", root))
    }

    /**
     * Source echoes and carets carry no severity, and are kept as information
     * rather than discarded: an unrecognised line is still the only
     * explanation the user is going to get.
     */
    @Test
    fun `unrecognised output survives as information`() {
        val diagnostic = ClangDiagnostics.parseLine("    4 | int x = y;", root)!!

        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity)
        assertEquals("4 | int x = y;", diagnostic.message)
    }

    @Test
    fun `a path outside the project is left absolute`() {
        val diagnostic = ClangDiagnostics.parseLine(
            "/toolchain/usr/include/stdio.h:100:1: error: something",
            root,
        )!!

        assertEquals("/toolchain/usr/include/stdio.h", diagnostic.file?.path)
    }
}
