package com.osamu.aide.engine.gradle

import com.osamu.aide.engine.api.BuildStage
import com.osamu.aide.engine.api.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Reading Gradle's console output, which is the only channel this engine has.
 *
 * Worth testing off-device because it is guesswork made explicit: Gradle prints
 * for people, not for parsers, and the failure mode of getting it wrong is a
 * progress bar that lies rather than an error anyone would notice.
 */
class GradleOutputTest {

    private val root = File("/data/user/0/pkg/projects/demo")

    @Test
    fun `a task that does work is a stage`() {
        assertEquals(BuildStage.COMPILE_JAVA, GradleOutput.stageOf("> Task :compileDebugJavaWithJavac"))
        assertEquals(BuildStage.COMPILE_KOTLIN, GradleOutput.stageOf("> Task :compileDebugKotlin"))
        assertEquals(BuildStage.LINK_RESOURCES, GradleOutput.stageOf("> Task :processDebugResources"))
        assertEquals(BuildStage.DEX, GradleOutput.stageOf("> Task :dexBuilderDebug"))
        assertEquals(BuildStage.PACKAGE, GradleOutput.stageOf("> Task :packageDebug"))
    }

    /**
     * A task Gradle skipped did no work, and reporting it as a stage would make
     * an incremental build look like a full one — the progress bar filling up
     * for things that never ran.
     */
    @Test
    fun `a skipped task is not a stage`() {
        assertNull(GradleOutput.stageOf("> Task :compileDebugJavaWithJavac UP-TO-DATE"))
        assertNull(GradleOutput.stageOf("> Task :processDebugResources NO-SOURCE"))
        assertNull(GradleOutput.stageOf("> Task :packageDebug FAILED"))
    }

    /**
     * Most of Gradle's two hundred tasks mean nothing to a user. Mapping them
     * all would be a flicker; mapping an unknown one to a guess would be worse
     * than silence.
     */
    @Test
    fun `an unrecognised task is not a stage`() {
        assertNull(GradleOutput.stageOf("> Task :checkDebugAarMetadata"))
        assertNull(GradleOutput.stageOf("> Task :preBuild"))
        assertNull(GradleOutput.stageOf("Downloading https://example.invalid/thing.jar"))
    }

    /** The variant is part of every task name, so matching is on the shape. */
    @Test
    fun `release variants map to the same stages as debug`() {
        assertEquals(BuildStage.COMPILE_JAVA, GradleOutput.stageOf("> Task :compileReleaseJavaWithJavac"))
        assertEquals(BuildStage.DEX, GradleOutput.stageOf("> Task :dexBuilderRelease"))
    }

    @Test
    fun `a compiler error becomes a tappable diagnostic`() {
        val diagnostic = GradleOutput.diagnosticOf(
            "${root.path}/src/main/java/demo/Main.java:7:15: error: cannot find symbol",
            root,
        )!!

        assertEquals(DiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals("src/main/java/demo/Main.java", diagnostic.file?.path)
        assertEquals(7, diagnostic.line)
        assertEquals(15, diagnostic.column)
        assertEquals("cannot find symbol", diagnostic.message)
    }

    @Test
    fun `a warning keeps its severity and a column is optional`() {
        val diagnostic = GradleOutput.diagnosticOf(
            "${root.path}/src/main/res/values/strings.xml:3: warning: unused resource",
            root,
        )!!

        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals(3, diagnostic.line)
    }

    /** Gradle's own chatter is not a compiler message. */
    @Test
    fun `ordinary output is not a diagnostic`() {
        assertNull(GradleOutput.diagnosticOf("> Task :compileDebugJavaWithJavac", root))
        assertNull(GradleOutput.diagnosticOf("BUILD SUCCESSFUL in 12s", root))
        assertNull(GradleOutput.diagnosticOf("", root))
    }

    /**
     * A path outside the project stays absolute: rewriting it would offer the
     * user a file they cannot open.
     */
    @Test
    fun `a path outside the project is left alone`() {
        val diagnostic = GradleOutput.diagnosticOf(
            "/home/gradle/caches/thing/Generated.java:1:1: error: broken",
            root,
        )!!

        assertEquals("/home/gradle/caches/thing/Generated.java", diagnostic.file?.path)
    }

    /**
     * The exit code alone tells the user nothing. Gradle's own sentence is the
     * one worth showing, and it is the first line after its marker.
     */
    @Test
    fun `gradle's explanation is lifted out of the failure block`() {
        val output = """
            > Task :compileDebugJavaWithJavac FAILED

            FAILURE: Build failed with an exception.

            * What went wrong:
            > Could not resolve androidx.core:core:1.99.0.

            * Try:
            > Run with --stacktrace option to get the stack trace.
        """.trimIndent()

        assertEquals("Could not resolve androidx.core:core:1.99.0.", GradleOutput.failureMessage(output))
    }

    /**
     * Gradle's first line is often a category and the cause is the indented
     * line under it. Reporting only the first leaves a message naming no cause
     * -- which is exactly what "Gradle could not start your build." is.
     */
    @Test
    fun `a headline with a cause under it keeps both`() {
        val output = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Gradle could not start your build.
            > Could not determine a usable wildcard IP for this machine.

            * Try:
            > Run with --stacktrace option to get the stack trace.
        """.trimIndent()

        assertEquals(
            "Gradle could not start your build. Could not determine a usable wildcard IP for this machine.",
            GradleOutput.failureMessage(output),
        )
    }

    @Test
    fun `output with no failure block has no message`() {
        assertNull(GradleOutput.failureMessage("BUILD SUCCESSFUL in 3s"))
    }
}
