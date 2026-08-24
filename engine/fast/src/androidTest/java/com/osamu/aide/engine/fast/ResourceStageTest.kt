package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.Diagnostic
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * aapt2, driven the way the engine drives it.
 *
 * These assert on what the stage produced, not on exit codes: aapt2 exits zero
 * on some errors it has already printed, so a passing exit code proves nothing
 * on its own.
 */
@RunWith(AndroidJUnit4::class)
class ResourceStageTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var stage: ResourceStage

    @Before
    fun setUp() {
        fixture = EngineTestFixture("resource-stage-test")
        fixture.assumeAapt2Supported()
        stage = ResourceStage(fixture.runner)
    }

    @Test
    fun compiles_resources_into_an_archive() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val workspace = fixture.workspace()

        val result = stage.compile(layout, workspace)

        assertTrue("compile failed: ${result.failure} ${result.diagnostics}", result.succeeded)
        val compiled = requireNotNull(result.value) { "no output produced" }
        assertTrue(compiled.isFile)

        // The archive holds one binary .flat per source resource file.
        val entries = ZipFile(compiled).use { zip -> zip.entries().toList().map { it.name } }
        assertTrue("expected a compiled strings resource, got $entries", entries.isNotEmpty())
    }

    @Test
    fun a_project_without_resources_is_not_a_failure() = runTest {
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        layout.resourceDir.deleteRecursively()

        val result = stage.compile(layout, fixture.workspace())

        assertTrue("should have succeeded: ${result.failure}", result.succeeded)
        assertEquals(null, result.value)
    }

    @Test
    fun links_an_apk_and_generates_R() = runTest {
        val project = fixture.project(applicationId = "com.example.demo")
        val layout = ProjectLayout.of(project)
        val workspace = fixture.workspace()

        assertTrue(stage.compile(layout, workspace).succeeded)
        val result = stage.link(layout, workspace, fixture.platform, debuggable = true)

        assertTrue("link failed: ${result.failure} ${result.diagnostics}", result.succeeded)

        // The linked APK carries resources and a *binary* manifest -- which is
        // what apksig's verifier needs and a hand-built zip cannot provide.
        val entries = ZipFile(workspace.linkedApk).use { zip ->
            zip.entries().toList().map { it.name }
        }
        assertTrue("no binary manifest in $entries", "AndroidManifest.xml" in entries)
        assertTrue("no resource table in $entries", "resources.arsc" in entries)

        // R.java is the join between the two halves of the build: without it
        // the generated activity does not compile.
        val generated = workspace.generatedJavaSources()
        assertEquals(
            "expected exactly one R.java, got ${generated.map { it.path }}",
            1,
            generated.size,
        )
        val rJava = generated.single()
        assertEquals(
            File(workspace.generatedJava, "com/example/demo/R.java").path,
            rJava.path,
        )
        assertTrue(
            "R.java does not declare the greeting string",
            rJava.readText().contains("greeting"),
        )
    }

    @Test
    fun a_broken_resource_fails_with_a_located_diagnostic() = runTest {
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        File(layout.resourceDir, "values/strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="greeting">unclosed
            </resources>
            """.trimIndent(),
        )

        val result = stage.compile(layout, fixture.workspace())

        assertTrue("a malformed resource should fail the stage", !result.succeeded)
        assertTrue("no error diagnostics: ${result.diagnostics}", result.diagnostics.hasErrors)

        // The point of parsing aapt2's output at all: the user gets the file to
        // open rather than a wall of text with an absolute cache path in it.
        //
        // Only the file, not a line: aapt2's XML parser reports a mismatched tag
        // against the document, not a position. Other resource errors do carry
        // one, which is why Diagnostic treats the line as optional rather than
        // pretending to a precision aapt2 does not always have.
        val located = result.diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(
            File("src/main/res/values/strings.xml"),
            located.file,
        )
    }

    /**
     * The streaming path carries the same diagnostics, canonicalised the same
     * way.
     *
     * Worth asserting separately from the returned [StageResult]: the reported
     * copy travels through a callback on the coroutine draining aapt2's pipes,
     * and a refactor that forgot to route it through [Aapt2Diagnostics] would
     * still fail the stage correctly while handing the editor an absolute cache
     * path it cannot open. Jump-to-error would break and nothing else would.
     */
    @Test
    fun diagnostics_are_reported_while_the_tool_runs() = runTest {
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        File(layout.resourceDir, "values/strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="greeting">unclosed
            </resources>
            """.trimIndent(),
        )

        val reported = mutableListOf<Diagnostic>()
        val result = stage.compile(layout, fixture.workspace()) { reported += it }

        assertEquals(
            "reported and returned diagnostics disagree",
            result.diagnostics,
            reported.toList(),
        )
        val located = reported.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(
            "the streamed path was not relativised",
            File("src/main/res/values/strings.xml"),
            located.file,
        )
    }
}
