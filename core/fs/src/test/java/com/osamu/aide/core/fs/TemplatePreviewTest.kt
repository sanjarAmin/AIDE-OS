package com.osamu.aide.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The preview describes what Create actually produces.
 *
 * **That equality is the whole point of the feature**, and it is the one thing
 * a preview can get wrong in a way the user only discovers afterwards. The
 * assertion is therefore a comparison against a real `write`, template by
 * template, rather than a check that the preview is non-empty.
 */
class TemplatePreviewTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the preview lists exactly what the template writes`() {
        ProjectTemplate.ALL.forEach { template ->
            val previewed = template
                .preview(name = "Demo App", scratch = temporaryFolder.newFolder("scratch-${template.id}"))
                .map { it.path }

            // The same template, written the way createProject writes it.
            val root = temporaryFolder.newFolder("real-${template.id}")
            val project = Project(
                name = "Demo App",
                rootDir = root,
                applicationId = ProjectDescriptor.applicationIdFor("Demo App"),
                language = template.language,
                engine = BuildEngine.FAST,
                lastOpenedAt = 0L,
                dependencies = template.dependencies,
            )
            template.write(project)
            val actual = root.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted()
                .toList()

            assertEquals("${template.id} previews something other than it writes", actual, previewed)
            assertTrue("${template.id} previews nothing", previewed.isNotEmpty())
        }
    }

    /**
     * The package directory shown is the one the project will have.
     *
     * The preview derives the application id from the typed name through
     * [ProjectDescriptor.applicationIdFor], which is also what
     * `ProjectsViewModel` uses. When those were two expressions in two files
     * this assertion is what would have caught them diverging.
     */
    @Test
    fun `the previewed package directory matches the application id a project would get`() {
        val previewed = BasicJavaApp
            .preview(name = "My Great App", scratch = temporaryFolder.newFolder("scratch"))
            .map { it.path }

        assertTrue(
            "the preview does not show the real package path: $previewed",
            previewed.any { it == "src/main/java/com/example/mygreatapp/MainActivity.java" },
        )
    }

    /** A blank name still previews, because the shape is what is being read. */
    @Test
    fun `a blank name previews under a placeholder rather than failing`() {
        val previewed = BasicJavaApp
            .preview(name = "   ", scratch = temporaryFolder.newFolder("scratch"))

        assertTrue("a blank name previewed nothing", previewed.isNotEmpty())
        assertTrue(
            "a blank name should not produce an empty package segment: $previewed",
            previewed.none { it.path.contains("//") },
        )
    }

    /** Previewing twice gives the same answer, and leaves nothing behind. */
    @Test
    fun `previewing is repeatable and leaves no scratch files`() {
        val scratch = temporaryFolder.newFolder("scratch")

        val first = NodeHttpServer.preview(name = "Server", scratch = scratch)
        val second = NodeHttpServer.preview(name = "Server", scratch = scratch)

        assertEquals(first, second)
        // The dialog previews on every selection and every keystroke of the
        // name, so anything left behind accumulates in the cache directory.
        assertEquals(
            "preview left files in the scratch directory: " +
                scratch.walkTopDown().filter { it.isFile }.map { it.name }.toList(),
            0,
            scratch.walkTopDown().count { it.isFile },
        )
    }
}
