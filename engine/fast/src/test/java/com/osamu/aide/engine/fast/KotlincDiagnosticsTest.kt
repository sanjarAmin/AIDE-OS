package com.osamu.aide.engine.fast

import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KotlincDiagnosticsTest {

    private val root = File("/projects/demo")

    @Test
    fun a_located_error_is_placed_in_the_project() {
        val parsed = KotlincDiagnostics.parse(
            "/projects/demo/src/main/java/com/example/Main.kt:11:34: error: Unresolved reference 'R'.",
            root,
        ).single()
        assertEquals(DiagnosticSeverity.ERROR, parsed.severity)
        assertEquals("src/main/java/com/example/Main.kt", parsed.file?.path)
        assertEquals(11, parsed.line)
        assertEquals(34, parsed.column)
        assertEquals("Unresolved reference 'R'.", parsed.message)
    }

    /**
     * kotlinc's complaint about the runtime it is on, printed on every build on
     * ART. It reached the Problems pane as the only entry on a clean build.
     */
    @Test
    fun the_compilers_remark_about_its_own_runtime_is_not_a_problem() {
        val parsed = KotlincDiagnostics.parse(
            "warning: your JDK doesn't seem to support mapped buffer unmapping, " +
                "so the slower (old) version of JAR FS will be used",
            root,
        )
        assertTrue("reported: $parsed", parsed.isEmpty())
    }

    @Test
    fun an_unlocated_warning_about_the_build_is_still_kept() {
        val parsed = KotlincDiagnostics.parse("warning: classpath entry points to a non-existent location: /x.jar", root)
        assertEquals(DiagnosticSeverity.WARNING, parsed.single().severity)
    }
}
