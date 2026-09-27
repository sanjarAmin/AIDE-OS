package com.osamu.aide.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeneratedDirectoriesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dir(path: String): File = File(temp.root, path).apply { mkdirs() }
    private fun file(path: String) = File(temp.root, path).apply { parentFile.mkdirs(); writeText("") }

    @Test
    fun a_build_beside_a_build_script_is_output() {
        file("app/build.gradle.kts")
        assertTrue(GeneratedDirectories.isGenerated(dir("app/build")))
        file("web/package.json")
        assertTrue(GeneratedDirectories.isGenerated(dir("web/build")))
    }

    /** The case every copy of the old rule got wrong. */
    @Test
    fun a_package_called_build_is_source() {
        file("app/build.gradle.kts")
        assertFalse(GeneratedDirectories.isGenerated(dir("app/src/main/java/com/example/build")))
    }

    @Test
    fun version_control_and_tool_state_are_always_output() {
        listOf(".git", ".gradle", ".idea", "node_modules").forEach {
            assertTrue(it, GeneratedDirectories.isGenerated(dir("deep/in/$it")))
        }
    }

    @Test
    fun siblings_are_not_listed_for_an_ordinary_name() {
        var asked = false
        GeneratedDirectories.isGenerated("src") { asked = true; emptyList() }
        assertFalse("listing a directory for every name entered is a cost on /sdcard", asked)
    }
}
