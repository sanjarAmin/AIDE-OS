package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The filesystem the assistant is allowed to touch.
 *
 * Weighted towards refusal rather than function. The happy paths here are
 * thin wrappers over `java.io.File` and would be hard to get wrong; the
 * interesting cases are all the ones where the model asks for something it
 * should not get, because those paths are chosen from text the model partly
 * read out of the user's own files.
 */
@RunWith(AndroidJUnit4::class)
class ProjectFilesTest {

    private lateinit var root: File
    private lateinit var files: ProjectFiles

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "project-files-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        files = ProjectFiles(root)

        write("src/main/java/com/example/Main.java", "class Main { void go() {} }")
        write("src/main/res/values/strings.xml", "<resources><string name=\"a\">A</string></resources>")
        write("build/generated/Junk.java", "// derived, should never be listed")
        write("README.md", "hello")
    }

    private fun write(path: String, content: String) {
        File(root, path).apply { parentFile?.mkdirs() }.writeText(content)
    }

    private fun ok(outcome: ProjectFiles.Outcome): String {
        assertTrue("expected success, got $outcome", outcome is ProjectFiles.Outcome.Ok)
        return (outcome as ProjectFiles.Outcome.Ok).content
    }

    private fun refused(outcome: ProjectFiles.Outcome): String {
        assertTrue("expected a refusal, got $outcome", outcome is ProjectFiles.Outcome.Refused)
        return (outcome as ProjectFiles.Outcome.Refused).reason
    }

    @Test
    fun a_file_inside_the_project_reads() {
        assertEquals("class Main { void go() {} }", ok(files.read("src/main/java/com/example/Main.java")))
    }

    /**
     * The escape guard, which is the reason this class exists.
     *
     * `../` is a string the model can emit as easily as any other, and the
     * paths it chooses come partly from text it read in the user's own files.
     * Without this, an assistant that edits code is an assistant that reads the
     * device.
     */
    @Test
    fun paths_outside_the_project_are_refused() {
        val escapes = listOf(
            "../secrets.txt",
            "../../../../data/data/com.other.app/databases/creds.db",
            "src/../../outside.txt",
        )

        escapes.forEach { path ->
            assertNull("resolve should refuse '$path'", files.resolve(path))
            assertTrue(
                "read should refuse '$path'",
                files.read(path) is ProjectFiles.Outcome.Refused,
            )
        }
    }

    /**
     * An absolute path is refused, not quietly reinterpreted.
     *
     * `File(root, "/etc/hosts")` does not escape -- Java joins it to
     * `<project>/etc/hosts` -- so this is a clarity problem rather than a
     * security one, and a nastier kind. The model asks for a specific file and
     * silently gets a different one that happens to be inside the project.
     * Refusing teaches it to send a relative path; succeeding wrongly teaches
     * it nothing.
     */
    @Test
    fun absolute_paths_are_refused_rather_than_silently_reinterpreted() {
        listOf("/etc/hosts", "/data/data/com.osamu.aide/shared_prefs/aide-ai.xml").forEach { path ->
            assertNull("resolve should refuse the absolute path '$path'", files.resolve(path))
            assertTrue(
                "read should refuse the absolute path '$path'",
                files.read(path) is ProjectFiles.Outcome.Refused,
            )
        }
    }

    /** And refusing to read is no use if writing still escapes. */
    @Test
    fun writing_outside_the_project_is_refused() {
        val outside = File(root.parentFile, "escaped.txt")
        outside.delete()

        refused(files.write("../escaped.txt", "should never be written"))

        assertFalse("a write escaped the project directory", outside.exists())
    }

    @Test
    fun a_missing_file_is_refused_rather_than_crashing() {
        assertTrue(refused(files.read("src/main/java/Nope.java")).contains("does not exist"))
    }

    @Test
    fun writing_creates_the_file_and_its_directories() {
        ok(files.write("src/main/java/com/example/deep/New.kt", "class New"))

        assertEquals("class New", File(root, "src/main/java/com/example/deep/New.kt").readText())
    }

    /**
     * Derived output is skipped.
     *
     * Not tidiness: `build/` on a real project dwarfs the source, and a model
     * that lists it spends its context on generated code that says nothing
     * about what the user wrote.
     */
    @Test
    fun listing_skips_build_output_and_version_control() {
        val listing = ok(files.list())

        assertTrue("the source file is missing from:\n$listing", "src/main/java/com/example/Main.java" in listing)
        assertTrue("README.md is missing from:\n$listing", "README.md" in listing)
        assertFalse("build output was listed:\n$listing", "build/" in listing)
    }

    @Test
    fun grep_reports_the_file_and_line_of_each_match() {
        val matches = ok(files.grep("void go"))

        assertTrue("expected a located match, got:\n$matches", matches.contains("Main.java:1:"))
    }

    @Test
    fun grep_with_no_matches_says_so_rather_than_failing() {
        assertTrue(ok(files.grep("nothingMatchesThis")).contains("No matches"))
    }

    @Test
    fun grep_does_not_search_build_output() {
        assertTrue(
            "grep reached into build/",
            ok(files.grep("derived, should never be listed")).contains("No matches"),
        )
    }

    /**
     * A file too large to read is refused with a reason.
     *
     * A model asking for a 40 MB generated file would otherwise spend the
     * context window and the bill in a single call, and it would look like the
     * model misbehaving rather than a missing limit.
     */
    @Test
    fun an_oversized_file_is_refused_with_a_usable_message() {
        write("huge.txt", "x".repeat(300 * 1024))

        val reason = refused(files.read("huge.txt"))
        assertTrue("the refusal should say what to do instead: $reason", reason.contains("grep"))
    }
}
