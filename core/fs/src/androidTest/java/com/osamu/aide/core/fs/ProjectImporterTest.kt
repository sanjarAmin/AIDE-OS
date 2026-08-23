package com.osamu.aide.core.fs

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.AppResult
import com.osamu.aide.core.common.DefaultDispatcherProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SAF import, through the real ContentResolver and a real DocumentsProvider.
 *
 * The traversal is the part worth testing: cursor columns, recursion, what is
 * skipped, and that the bytes actually arrive. None of that can be checked off
 * a device, and none of it fails loudly when it is wrong -- a mis-read column
 * index gives an empty project, not an exception.
 */
@RunWith(AndroidJUnit4::class)
class ProjectImporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().context
    private val dispatchers = DefaultDispatcherProvider()

    private lateinit var source: File
    private lateinit var workspace: File

    private val tree = DocumentsContract.buildTreeDocumentUri(
        FakeDocumentsProvider.AUTHORITY,
        FakeDocumentsProvider.ROOT_DOCUMENT_ID,
    )

    @Before
    fun setUp() {
        val scratch = File(context.cacheDir, "import-test-${System.nanoTime()}")
        source = File(scratch, "MyApp").apply { mkdirs() }
        workspace = File(scratch, "workspace")
        FakeDocumentsProvider.root = source
    }

    @After
    fun tearDown() {
        source.parentFile?.deleteRecursively()
    }

    private fun write(path: String, contents: String) {
        val file = File(source, path)
        file.parentFile?.mkdirs()
        file.writeText(contents)
    }

    /** The smallest thing that counts as an Android module. */
    private fun writeModule(prefix: String = "") {
        write(
            "${prefix}src/main/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.imported" />
            """.trimIndent(),
        )
        write("${prefix}src/main/java/com/example/imported/Main.java", "class Main {}\n")
    }

    private fun import(
        maximumBytes: Long = ProjectImporter.DEFAULT_MAXIMUM_BYTES,
    ): AppResult<Project> = runBlocking {
        ProjectImporter(context, workspace, dispatchers, maximumBytes).import(tree)
    }

    @Test
    fun a_module_folder_is_copied_into_the_workspace() {
        writeModule()

        val project = (import() as AppResult.Success).value

        assertEquals("MyApp", project.name)
        assertEquals(File(workspace, "MyApp"), project.rootDir)
        assertEquals("com.example.imported", project.applicationId)
        assertEquals(SourceLanguage.JAVA, project.language)

        // The files, not just the descriptor: an import that creates an empty
        // project would pass every assertion about the Project object.
        assertEquals(
            "class Main {}\n",
            File(project.rootDir, "src/main/java/com/example/imported/Main.java").readText(),
        )
        assertTrue(project.descriptorFile.isFile)
        assertEquals(project.applicationId, ProjectDescriptor.read(project.rootDir)?.applicationId)
    }

    @Test
    fun a_gradle_root_with_one_module_imports_that_module() {
        // What people actually pick: the folder they cloned, holding app/.
        writeModule(prefix = "app/")
        write("settings.gradle", "include ':app'\n")

        val project = (import() as AppResult.Success).value

        // Named after the folder that was picked. A workspace of projects all
        // called "app" would be no use to anyone.
        assertEquals("MyApp", project.name)
        assertEquals(File(workspace, "MyApp"), project.rootDir)
        assertTrue(File(project.rootDir, "src/main/AndroidManifest.xml").isFile)
        assertFalse(
            "the Gradle root's own files came along",
            File(project.rootDir, "settings.gradle").exists(),
        )
    }

    @Test
    fun a_folder_with_two_modules_is_refused_rather_than_guessed_at() {
        writeModule(prefix = "app/")
        writeModule(prefix = "library/")

        val failure = import() as AppResult.Failure
        assertTrue(failure.error.message, failure.error.message.contains("AndroidManifest"))
        assertFalse("something was imported anyway", workspace.exists())
    }

    @Test
    fun build_output_and_version_control_are_not_copied() {
        writeModule()
        write("build/intermediates/huge.bin", "x".repeat(1000))
        write(".git/objects/pack/whatever", "x".repeat(1000))
        write(".gradle/caches/thing", "x")
        write("src/main/java/.hidden", "x")

        val project = (import() as AppResult.Success).value

        assertFalse("build/ was copied", File(project.rootDir, "build").exists())
        assertFalse(".git was copied", File(project.rootDir, ".git").exists())
        assertFalse(".gradle was copied", File(project.rootDir, ".gradle").exists())
        assertFalse(
            "a hidden file was copied",
            File(project.rootDir, "src/main/java/.hidden").exists(),
        )
    }

    @Test
    fun a_folder_larger_than_the_limit_is_refused_before_anything_is_copied() {
        writeModule()
        write("src/main/assets/big.bin", "x".repeat(4096))

        val failure = import(maximumBytes = 1024) as AppResult.Failure

        assertTrue(failure.error.message, failure.error.message.contains("limited to"))
        assertFalse("a refused import still wrote files", workspace.exists())
    }

    @Test
    fun a_folder_that_is_not_a_module_is_refused() {
        write("notes.txt", "nothing to see")

        val failure = import() as AppResult.Failure
        assertTrue(failure.error.message, failure.error.message.contains("AndroidManifest"))
    }

    @Test
    fun kotlin_sources_make_it_a_kotlin_project() {
        writeModule()
        write("src/main/java/com/example/imported/Extra.kt", "class Extra\n")

        val project = (import() as AppResult.Success).value
        assertEquals(SourceLanguage.KOTLIN, project.language)
    }

    @Test
    fun importing_the_same_folder_twice_is_refused_rather_than_merged() {
        writeModule()
        import()

        val failure = import() as AppResult.Failure
        assertTrue(failure.error.message, failure.error.message.contains("already exists"))
    }
}
