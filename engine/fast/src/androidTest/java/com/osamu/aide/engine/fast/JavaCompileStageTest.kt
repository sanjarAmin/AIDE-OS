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

    // ---- Incremental compilation. See IncrementalJava. ----

    private fun write(layout: ProjectLayout, name: String, body: String) =
        File(layout.javaDir, "com/example/demo/$name.java").apply {
            parentFile?.mkdirs()
            writeText("package com.example.demo;\n$body\n")
        }

    /** One build, as the engine runs it: a fresh workspace, the kept cache. */
    private suspend fun build(layout: ProjectLayout, cache: File): Pair<BuildWorkspace, StageResult<File>> {
        val workspace = fixture.workspace()
        linkResources(layout, workspace)
        val sources = layout.javaSources() + workspace.generatedJavaSources()
        return workspace to javac.compile(sources, fixture.platform, workspace, layout.root, cacheDir = cache)
    }

    private fun BuildWorkspace.classBytes(name: String) = File(classes, "com/example/demo/$name.class").readBytes()

    private val readerSource = "public final class Reader { public int read() { return Limits.MAX + new Worker().work(); } }"

    /**
     * **A body edit recompiles that source and nothing else.** Asserted from the
     * cache, whose copy of an untouched class is not rewritten, and from the
     * result, which must hold the new code.
     */
    @Test
    fun a_body_edit_recompiles_only_its_source() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "java-cache")
        write(layout, "Limits", "public final class Limits { public static final int MAX = 1; }")
        write(layout, "Worker", "public final class Worker { public int work() { return 1; } }")
        write(layout, "Reader", readerSource)
        // Past the racy window: a source written moments before the state is
        // saved is always read again next build, which is correct and would
        // recompile Reader here for that reason alone. See IncrementalJava.
        Thread.sleep(IncrementalJava.RACY_MILLIS + 200)
        val (first, firstResult) = build(layout, cache)
        assertTrue(firstResult.succeeded)
        val workerBefore = first.classBytes("Worker")
        val readerStamp = File(cache, "classes/com/example/demo/Reader.class").lastModified()

        Thread.sleep(1_100)
        write(layout, "Worker", "public final class Worker { public int work() { return 42; } }")
        val (workspace, result) = build(layout, cache)

        assertTrue("rebuild failed: ${result.failure}", result.succeeded)
        assertEquals("an unchanged class was recompiled", readerStamp, File(cache, "classes/com/example/demo/Reader.class").lastModified())
        assertTrue("the edit is not in the output", !workerBefore.contentEquals(workspace.classBytes("Worker")))
        assertTrue(
            "every other class must be in the output too",
            listOf("Limits", "Reader", "MainActivity").all { File(workspace.classes, "com/example/demo/$it.class").isFile },
        )
    }

    /**
     * **A constant edit recompiles its readers**, because ECJ copied the old
     * value into them. `Reader` names no class of `Limits` at runtime -- only
     * its bytecode changes, from `iconst_1` to `iconst_2`.
     */
    @Test
    fun a_constant_edit_recompiles_the_classes_that_inlined_it() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "java-cache")
        write(layout, "Limits", "public final class Limits { public static final int MAX = 1; }")
        write(layout, "Worker", "public final class Worker { public int work() { return 1; } }")
        write(layout, "Reader", readerSource)
        val (first, _) = build(layout, cache)
        val before = first.classBytes("Reader")

        write(layout, "Limits", "public final class Limits { public static final int MAX = 2; }")
        val (second, result) = build(layout, cache)

        assertTrue("rebuild failed: ${result.failure}", result.succeeded)
        assertTrue("Reader still holds the old constant", !before.contentEquals(second.classBytes("Reader")))
    }

    /** A removed source's classes do not survive into the next build. */
    @Test
    fun a_deleted_source_leaves_no_class_behind() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "java-cache")
        val extra = write(layout, "Extra", "public final class Extra {}")
        assertTrue(build(layout, cache).second.succeeded)

        extra.delete()
        val (workspace, result) = build(layout, cache)

        assertTrue(result.succeeded)
        assertTrue("Extra.class outlived its source", !File(workspace.classes, "com/example/demo/Extra.class").exists())
    }

    /**
     * A build with nothing changed still reports the warnings it had.
     * Nothing is compiled, so they come from the kept state; without that the
     * Problems pane empties on every unchanged rebuild.
     */
    @Test
    fun an_unchanged_rebuild_keeps_its_warnings() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "java-cache")
        write(layout, "Unused", "import java.util.List;\npublic final class Unused {}")
        val first = build(layout, cache).second
        val warnings = first.diagnostics.filter { it.severity == DiagnosticSeverity.WARNING }
        assertTrue("the fixture should produce a warning: ${first.diagnostics}", warnings.isNotEmpty())

        val (workspace, second) = build(layout, cache)

        assertTrue(second.succeeded)
        assertEquals(warnings, second.diagnostics.filter { it.severity == DiagnosticSeverity.WARNING })
        assertTrue(File(workspace.classes, "com/example/demo/Unused.class").isFile)
    }

    /** A broken edit fails, and fixing it builds -- the cache cannot keep a failure. */
    @Test
    fun a_failed_build_does_not_poison_the_next() = runTest {
        val layout = ProjectLayout.of(fixture.project())
        val cache = File(fixture.workDir, "java-cache")
        write(layout, "Worker", "public final class Worker { public int work() { return 1; } }")
        assertTrue(build(layout, cache).second.succeeded)

        write(layout, "Worker", "public final class Worker { public int work() { return nope; } }")
        assertTrue(!build(layout, cache).second.succeeded)

        write(layout, "Worker", "public final class Worker { public int work() { return 3; } }")
        val (workspace, result) = build(layout, cache)
        assertTrue("fixed source did not build: ${result.failure}", result.succeeded)
        assertTrue(File(workspace.classes, "com/example/demo/MainActivity.class").isFile)
    }
}
