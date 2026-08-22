package com.osamu.aide.engine.fast

import com.android.tools.r8.origin.Origin
import com.android.tools.r8.origin.PathOrigin
import com.android.tools.r8.position.Position
import com.android.tools.r8.position.TextPosition
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import com.android.tools.r8.Diagnostic as R8Diagnostic

class DexDiagnosticsTest {

    private val root = File("/data/user/0/com.osamu.aide/files/projects/demo")

    private class FakeDiagnostic(
        private val message: String,
        private val origin: Origin = Origin.unknown(),
        private val position: Position = Position.UNKNOWN,
    ) : R8Diagnostic {
        override fun getOrigin(): Origin = origin
        override fun getPosition(): Position = position
        override fun getDiagnosticMessage(): String = message
    }

    @Test
    fun `severity comes from the handler method, not the message`() {
        // D8 puts no severity word in the text; a message that happens to
        // contain "warning" is still an error if D8 called error().
        val diagnostic = DexDiagnostics.convert(
            FakeDiagnostic("Ignoring a warning-suppressing annotation"),
            DiagnosticSeverity.ERROR,
            root,
        )

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
    }

    @Test
    fun `a path origin inside the project is reported relative to it`() {
        val diagnostic = DexDiagnostics.convert(
            FakeDiagnostic(
                "Invalid class file",
                PathOrigin(File(root, "libs/broken.jar").toPath()),
            ),
            DiagnosticSeverity.ERROR,
            root,
        )

        assertEquals(File("libs/broken.jar"), diagnostic.file)
    }

    @Test
    fun `an unknown origin leaves the diagnostic unlocated`() {
        // Most D8 errors are about the compilation as a whole. Inventing a file
        // for them would put the error on a line that has nothing wrong with it.
        val diagnostic = DexDiagnostics.convert(
            FakeDiagnostic("Compilation failed to complete"),
            DiagnosticSeverity.ERROR,
            root,
        )

        assertNull(diagnostic.file)
        assertEquals(Diagnostic.UNKNOWN, diagnostic.line)
        assertEquals(Diagnostic.UNKNOWN, diagnostic.column)
    }

    @Test
    fun `a text position supplies line and column`() {
        val diagnostic = DexDiagnostics.convert(
            FakeDiagnostic(
                "Type is defined twice",
                PathOrigin(File(root, "src/main/java/demo/Dup.java").toPath()),
                TextPosition(0L, 12, 5),
            ),
            DiagnosticSeverity.WARNING,
            root,
        )

        assertEquals(12, diagnostic.line)
        assertEquals(5, diagnostic.column)
    }

    @Test
    fun `a placeholder position is reported as unknown, not as line zero`() {
        // D8 uses a zero line for "somewhere in this file". Passing that through
        // would point the editor's gutter at a line that does not exist.
        val diagnostic = DexDiagnostics.convert(
            FakeDiagnostic(
                "Invalid class file",
                PathOrigin(File(root, "libs/broken.jar").toPath()),
                TextPosition(0L, 0, TextPosition.UNKNOWN_COLUMN),
            ),
            DiagnosticSeverity.ERROR,
            root,
        )

        assertEquals(Diagnostic.UNKNOWN, diagnostic.line)
        assertEquals(Diagnostic.UNKNOWN, diagnostic.column)
    }
}
