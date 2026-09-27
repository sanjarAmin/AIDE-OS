package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aapt2DiagnosticsTest {

    private val root = File("/data/user/0/com.osamu.aide/files/projects/demo")

    @Test
    fun `parses file line and column`() {
        val output = "$root/src/main/res/values/strings.xml:4:5: error: unexpected element"

        val diagnostic = Aapt2Diagnostics.parse(output, root).single()

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("unexpected element", diagnostic.message)
        assertEquals(File("src/main/res/values/strings.xml"), diagnostic.file)
        assertEquals(4, diagnostic.line)
        assertEquals(5, diagnostic.column)
    }

    @Test
    fun `parses a location with no column`() {
        val output = "$root/src/main/AndroidManifest.xml:7: error: missing attribute"

        val diagnostic = Aapt2Diagnostics.parse(output, root).single()

        assertEquals(7, diagnostic.line)
        assertEquals(Diagnostic.UNKNOWN, diagnostic.column)
    }

    @Test
    fun `parses a bare error with no location`() {
        val diagnostic = Aapt2Diagnostics.parse("error: failed to open APK", root).single()

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("failed to open APK", diagnostic.message)
        assertNull(diagnostic.file)
    }

    @Test
    fun `paths outside the project are left absolute`() {
        // A failure reading android.jar is not a fault in the user's project,
        // and rewriting the path as if it were would be actively misleading.
        val output = "/opt/sdk/android.jar: error: failed to load"

        val diagnostic = Aapt2Diagnostics.parse(output, root).single()

        assertEquals(File("/opt/sdk/android.jar"), diagnostic.file)
    }

    @Test
    fun `warnings are not errors`() {
        val output = "$root/src/main/res/values/strings.xml:2:3: warning: unused resource"

        val diagnostics = Aapt2Diagnostics.parse(output, root)

        assertEquals(DiagnosticSeverity.WARNING, diagnostics.single().severity)
        assertFalse(diagnostics.hasErrors)
    }

    @Test
    fun `unrecognised output is kept rather than dropped`() {
        // aapt2's messages are not a stable format. Silently discarding a line
        // we cannot parse would leave a failed build with nothing to show.
        val diagnostics = Aapt2Diagnostics.parse("something entirely unexpected", root)

        assertEquals(1, diagnostics.size)
        assertEquals(DiagnosticSeverity.INFO, diagnostics.single().severity)
        assertEquals("something entirely unexpected", diagnostics.single().message)
    }

    @Test
    fun `blank lines are ignored`() {
        assertTrue(Aapt2Diagnostics.parse("\n   \n\n", root).isEmpty())
    }

    @Test
    fun `a multi-line report yields one diagnostic per line`() {
        val output = """
            $root/src/main/res/values/strings.xml:4:5: error: unexpected element
            $root/src/main/res/values/colors.xml:2: warning: unused
            error: failed to process resources
        """.trimIndent()

        val diagnostics = Aapt2Diagnostics.parse(output, root)

        assertEquals(3, diagnostics.size)
        assertEquals(2, diagnostics.count { it.severity == DiagnosticSeverity.ERROR })
        assertTrue(diagnostics.hasErrors)
    }
}
