package com.osamu.aide.core.fs

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Turning a directory already on disk into a project, and finding it again.
 *
 * The two halves belong together: adoption decides *where* the descriptor goes
 * and listing decides where it is looked for, and the pair of them disagreeing
 * is a repository that clones perfectly and never appears — with no error
 * anywhere to say why. That happened, which is why the listing assertion is
 * here rather than left to the caller.
 */
class ProjectAdoptionTest {

    private lateinit var workspaceRoot: File
    private lateinit var adoption: ProjectAdoption
    private lateinit var repository: FileProjectRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workspaceRoot = File(context.cacheDir, "adoption-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val dispatchers = DefaultDispatcherProvider()
        adoption = ProjectAdoption(dispatchers)
        repository = FileProjectRepository(workspaceRoot, dispatchers)
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    /** Writes a module under [at], relative to a directory in the workspace. */
    private fun module(
        directory: String,
        at: String? = null,
        packageName: String? = "com.example.adopted",
        kotlin: Boolean = false,
    ): File {
        val root = File(workspaceRoot, directory)
        val base = if (at == null) root else File(root, at)
        File(base, "src/main/java/com/example/adopted").mkdirs()
        File(base, "src/main/AndroidManifest.xml").writeText(
            if (packageName == null) {
                """<manifest><application/></manifest>"""
            } else {
                """<manifest package="$packageName"><application/></manifest>"""
            },
        )
        val source = if (kotlin) "Main.kt" else "Main.java"
        File(base, "src/main/java/com/example/adopted/$source").writeText("// source\n")
        return root
    }

    private fun <T> AppResult<T>.orFail(): T = when (this) {
        is AppResult.Success -> value
        is AppResult.Failure -> throw AssertionError(error.message)
    }

    private fun AppResult<*>.failureMessage(): String = when (this) {
        is AppResult.Failure -> error.message
        is AppResult.Success -> throw AssertionError("expected a failure, got $value")
    }

    @Test
    fun a_module_at_the_top_level_is_adopted_as_itself() = runTest {
        val directory = module("flat")

        val project = adoption.adopt(directory, "Flat").orFail()

        assertEquals(directory, project.rootDir)
        assertEquals("Flat", project.name)
        assertEquals("com.example.adopted", project.applicationId)
        assertTrue(project.descriptorFile.isFile)
    }

    /**
     * The shape every cloned Android repository has: a Gradle root holding a
     * single module. The project is the module.
     */
    @Test
    fun a_single_module_in_a_subfolder_is_the_project() = runTest {
        val directory = module("cloned", at = "app")

        val project = adoption.adopt(directory, "Cloned").orFail()

        assertEquals(File(directory, "app"), project.rootDir)
    }

    /**
     * And it is still listed, though its descriptor is now a *grandchild* of
     * the workspace root.
     *
     * This is the assertion that would have caught the bug: adoption succeeded,
     * the descriptor was written, and the projects screen stayed empty.
     */
    @Test
    fun a_project_in_a_subfolder_is_still_listed() = runTest {
        adoption.adopt(module("cloned", at = "app"), "Cloned").orFail()

        val listed = repository.listProjects().orFail()

        assertEquals(1, listed.size)
        assertEquals("Cloned", listed.single().name)
        assertEquals("app", listed.single().rootDir.name)
    }

    /**
     * Exactly one level. Deeper is someone's build output or a vendored copy,
     * and a projects list that turns those up is worse than one that misses an
     * unusual layout.
     *
     * Adopted from `outer/nested`, which puts the descriptor at
     * `outer/nested/app` -- three levels down. Adopting `outer` itself would
     * not reach it at all: `nested` is not a module, and the search is one
     * level there too.
     */
    @Test
    fun a_project_three_levels_down_is_not_listed() = runTest {
        module("outer", at = "nested/app")

        adoption.adopt(File(workspaceRoot, "outer/nested"), "Buried").orFail()

        assertTrue(repository.listProjects().orFail().isEmpty())
    }

    /** A project containing a project is one project. */
    @Test
    fun a_descriptor_at_the_top_stops_the_descent() = runTest {
        val directory = module("outer")
        module("outer", at = "app")
        adoption.adopt(directory, "Outer").orFail()
        // A second descriptor inside it, as a vendored copy would have.
        ProjectDescriptor.write(
            Project(
                name = "Inner",
                rootDir = File(directory, "app"),
                applicationId = "com.example.inner",
                language = SourceLanguage.JAVA,
                engine = BuildEngine.FAST,
                lastOpenedAt = 0L,
            ),
        )

        assertEquals(listOf("Outer"), repository.listProjects().orFail().map { it.name })
    }

    /**
     * Two modules have no right answer, and picking one silently leaves the
     * user with a project quietly missing half their code.
     */
    @Test
    fun two_modules_are_refused_rather_than_guessed_at() = runTest {
        val directory = module("multi", at = "app")
        module("multi", at = "library")

        assertTrue("No Android module found" in adoption.adopt(directory, "Multi").failureMessage())
        assertFalse(adoption.isAdoptable(directory))
    }

    @Test
    fun a_directory_with_no_module_is_refused() = runTest {
        val directory = File(workspaceRoot, "readme-only").apply { mkdirs() }
        File(directory, "README.md").writeText("nothing to build\n")

        assertTrue("No Android module found" in adoption.adopt(directory, "Readme").failureMessage())
    }

    /** Any Kotlin in it makes it a Kotlin project. */
    @Test
    fun kotlin_sources_make_it_a_kotlin_project() = runTest {
        val directory = module("kotlin-app", kotlin = true)

        assertEquals(SourceLanguage.KOTLIN, adoption.adopt(directory, "K").orFail().language)
    }

    /**
     * A manifest with no `package` is ordinary rather than broken: AGP 7 moved
     * the application id into the Gradle build. One is derived from the name.
     */
    @Test
    fun a_manifest_without_a_package_gets_a_derived_application_id() = runTest {
        val directory = module("no-package", packageName = null)

        assertEquals(
            "com.example.nopackage",
            adoption.adopt(directory, "No Package").orFail().applicationId,
        )
    }
}
