package com.osamu.aide.engine.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading back what a Gradle build recorded for the editor.
 *
 * The writing half is an init script and was run against AGP 9.3.2, on a
 * desktop and in the app -- see `tools/bench/FINDINGS.md`. This pins the
 * reading half, against the shapes those builds really left behind.
 */
class GradleEditorInputsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(root: File, path: String, text: String = ""): File =
        File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun every_modules_sources_are_found_and_build_output_is_not() {
        val root = temp.newFolder("project")
        file(root, "app/src/main/java/a/Main.java")
        file(root, "lib/src/main/kotlin/b/Lib.kt")
        file(root, "lib/src/test/java/b/LibTest.java")
        // Generated code that happens to sit in a src/main/java shape.
        file(root, "app/build/generated/src/main/java/x/Gen.java")

        assertEquals(
            listOf("app/src/main/java", "lib/src/main/kotlin"),
            GradleEditorInputs.sourceRoots(root).map { it.relativeTo(root).invariantSeparatorsPath },
        )
    }

    @Test
    fun the_recorded_classpath_is_read_without_the_projects_own_outputs() {
        val root = temp.newFolder("project")
        file(root, "app/build.gradle.kts")
        file(root, "lib/build.gradle")
        val cache = temp.newFolder("gradle-home")
        val aar = file(cache, "transforms/1/transformed/core-1.13.1-api.jar")
        val jar = file(cache, "files-2.1/annotation-jvm-1.6.0.jar")
        val otherModule = file(root, "lib/build/intermediates/compile_library_classes_jar/debug/x/classes.jar")
        val appR = file(root, "app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar")
        file(
            root,
            "app/build/aide/compile-classpath-debug.txt",
            listOf(appR, otherModule, aar, jar, File(cache, "gone.jar")).joinToString("\n") { it.absolutePath },
        )

        val classpath = GradleEditorInputs.classpath(root)

        assertTrue("an AAR's jar is missing: $classpath", aar in classpath)
        assertTrue("a plain jar is missing: $classpath", jar in classpath)
        assertTrue(
            "another module's classes were put beside its sources: $classpath",
            otherModule !in classpath,
        )
        assertTrue("R has no source, so its jar must stay: $classpath", classpath.any { it.name == "R.jar" })
        assertTrue("a jar that no longer exists was kept", classpath.none { it.name == "gone.jar" })
    }

    @Test
    fun a_project_never_built_has_no_classpath_yet() {
        val root = temp.newFolder("project")
        file(root, "app/src/main/java/a/Main.java")
        assertEquals(emptyList<File>(), GradleEditorInputs.classpath(root))
    }

    @Test
    fun the_init_script_records_debug_variants_of_apps_and_libraries() {
        val script = GradleEditorInputs.INIT_SCRIPT
        assertTrue(script, "com.android.application" in script && "com.android.library" in script)
        assertTrue(script, "withBuildType('debug')" in script)
        assertTrue(script, "compileClasspath" in script)
        assertTrue(script, "compile-classpath-debug.txt" in script)
    }
}
