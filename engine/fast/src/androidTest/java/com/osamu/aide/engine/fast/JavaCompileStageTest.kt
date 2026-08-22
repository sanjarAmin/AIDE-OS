package com.osamu.aide.engine.fast

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DefaultDispatcherProvider
import com.osamu.aide.core.fs.ProjectLayout
import com.osamu.aide.engine.api.DiagnosticSeverity
import com.osamu.aide.engine.api.hasErrors
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ECJ, on the device, compiling the template project against a linked R.java.
 *
 * This is the join between the two halves of the build, so the resource stages
 * run first for real rather than being stubbed: the point is that R.java exists
 * and the compiler was handed it.
 */
@RunWith(AndroidJUnit4::class)
class JavaCompileStageTest {

    private lateinit var fixture: EngineTestFixture
    private lateinit var resources: ResourceStage
    private lateinit var javac: JavaCompileStage

    @Before
    fun setUp() {
        fixture = EngineTestFixture("java-compile-stage-test")
        fixture.assumeAapt2Supported()
        resources = ResourceStage(fixture.runner)
        javac = JavaCompileStage(DefaultDispatcherProvider())
    }

    private suspend fun linkResources(layout: ProjectLayout, workspace: BuildWorkspace) {
        assertTrue(resources.compile(layout, workspace).succeeded)
        assertTrue(resources.link(layout, workspace, fixture.platform, debuggable = true).succeeded)
    }

    @Test
    fun compiles_the_template_against_generated_R() = runTest {
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        val workspace = fixture.workspace()
        linkResources(layout, workspace)

        val sources = layout.javaSources() + workspace.generatedJavaSources()
        val result = javac.compile(sources, fixture.platform, workspace, layout.root)

        assertTrue("compile failed: ${result.failure} ${result.diagnostics}", result.succeeded)

        val classes = workspace.classes.walkTopDown().filter { it.extension == "class" }.toList()
        assertTrue("no class files produced", classes.isNotEmpty())
        assertTrue(
            "MainActivity was not compiled: ${classes.map { it.name }}",
            classes.any { it.name == "MainActivity.class" },
        )
    }

    @Test
    fun a_lambda_compiles_because_the_platform_stubs_are_on_the_classpath() = runTest {
        // android.jar has no java.lang.invoke.LambdaMetafactory, so without the
        // stubs this fails -- and with it, so does every real Java project.
        // See tools/ecj/FINDINGS.md.
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        val workspace = fixture.workspace()
        linkResources(layout, workspace)

        File(layout.javaDir, "com/example/demo/Lambdas.java").writeText(
            """
            package com.example.demo;

            import java.util.List;
            import java.util.stream.Collectors;

            public final class Lambdas {
                public static List<String> shout(List<String> words) {
                    return words.stream().map(w -> w + "!").collect(Collectors.toList());
                }
            }
            """.trimIndent(),
        )

        val sources = layout.javaSources() + workspace.generatedJavaSources()
        val result = javac.compile(sources, fixture.platform, workspace, layout.root)

        assertTrue("lambda failed to compile: ${result.failure} ${result.diagnostics}", result.succeeded)
    }

    @Test
    fun a_compile_error_reports_a_located_diagnostic() = runTest {
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        val workspace = fixture.workspace()
        linkResources(layout, workspace)

        val activity = layout.javaSources().single()
        activity.writeText(activity.readText().replace("setContentView(text);", "setContentView(nope);"))

        val sources = layout.javaSources() + workspace.generatedJavaSources()
        val result = javac.compile(sources, fixture.platform, workspace, layout.root)

        assertTrue("a broken source should fail the stage", !result.succeeded)
        assertTrue("no error diagnostics: ${result.diagnostics}", result.diagnostics.hasErrors)

        val error = result.diagnostics.first { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(File("src/main/java/com/example/demo/MainActivity.java"), error.file)
        assertTrue("diagnostic has no line: ${error.describe()}", error.hasLocation)
        assertEquals("1 error.", result.failure)
    }

    @Test
    fun a_missing_resource_reference_fails_rather_than_silently_dropping_a_class() = runTest {
        // If R.java were never passed to the compiler this is what it would look
        // like, so this pins the two halves of the build together.
        val project = fixture.project()
        val layout = ProjectLayout.of(project)
        val workspace = fixture.workspace()
        linkResources(layout, workspace)

        val result = javac.compile(layout.javaSources(), fixture.platform, workspace, layout.root)

        assertTrue("compiling without R.java should fail", !result.succeeded)
        assertTrue(result.diagnostics.hasErrors)
    }
}
