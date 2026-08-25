package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The tools as the model sees them, and the gate in front of the one that bites.
 *
 * `ProjectFilesTest` covers what the operations do. This covers the layer that
 * decides *whether they run at all*, which is where the plan's "gate every
 * mutating tool behind an explicit confirmation" either holds or does not.
 */
@RunWith(AndroidJUnit4::class)
class ProjectToolsetTest {

    private lateinit var root: File
    private lateinit var toolset: ProjectToolset

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "toolset-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }.writeText("fun main() = Unit\n")
        toolset = ProjectToolset(ProjectFiles(root))
    }

    private fun ok(outcome: ProjectFiles.Outcome): String {
        assertTrue("expected success, got $outcome", outcome is ProjectFiles.Outcome.Ok)
        return (outcome as ProjectFiles.Outcome.Ok).content
    }

    /**
     * The safety property the whole class exists for.
     *
     * The model can ask to overwrite a file on any turn, including one the user
     * is not watching. A dispatcher that runs it anyway and leaves the prompt to
     * the UI has already lost -- the confirmation has to be something the caller
     * cannot forget, so this checks it fails closed.
     */
    @Test
    fun a_mutating_tool_will_not_run_without_approval() {
        val outcome = toolset.execute(
            "edit_file",
            mapOf("path" to "src/Main.kt", "content" to "destroyed"),
            approved = false,
        )

        assertTrue("edit_file ran unapproved: $outcome", outcome is ProjectFiles.Outcome.Refused)
        assertEquals(
            "the file was modified despite the refusal",
            "fun main() = Unit\n",
            File(root, "src/Main.kt").readText(),
        )
    }

    /** And approval is the only thing standing in the way. */
    @Test
    fun an_approved_mutating_tool_runs() {
        ok(
            toolset.execute(
                "edit_file",
                mapOf("path" to "src/Main.kt", "content" to "fun main() = println(1)\n"),
                approved = true,
            ),
        )

        assertEquals("fun main() = println(1)\n", File(root, "src/Main.kt").readText())
    }

    /**
     * Read-only tools must not need approval.
     *
     * If they did, every turn would prompt and users would confirm without
     * reading -- which is how a confirmation dialog stops being a safety
     * feature and starts being a speed bump people learn to tap through.
     */
    @Test
    fun read_only_tools_run_without_approval() {
        assertTrue(ok(toolset.execute("list_files", emptyMap())).contains("src/Main.kt"))
        assertTrue(ok(toolset.execute("read_file", mapOf("path" to "src/Main.kt"))).contains("fun main"))
        assertTrue(ok(toolset.execute("grep", mapOf("query" to "fun main"))).contains("Main.kt:1:"))
    }

    @Test
    fun only_edit_file_is_marked_mutating() {
        val mutating = toolset.all().filter { it.risk == ToolRisk.MUTATING }.map { it.name }
        assertEquals(listOf("edit_file"), mutating)
    }

    @Test
    fun an_unknown_tool_is_refused_by_name() {
        val outcome = toolset.execute("rm_rf", emptyMap())
        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertTrue((outcome as ProjectFiles.Outcome.Refused).reason.contains("rm_rf"))
    }

    @Test
    fun a_tool_called_without_its_required_input_is_refused() {
        assertTrue(toolset.execute("read_file", emptyMap()) is ProjectFiles.Outcome.Refused)
        assertTrue(toolset.execute("grep", emptyMap()) is ProjectFiles.Outcome.Refused)
        assertTrue(
            toolset.execute("edit_file", mapOf("path" to "x"), approved = true)
                is ProjectFiles.Outcome.Refused,
        )
    }

    /**
     * The definitions have to survive being built, because they are the first
     * thing in the cached prefix and a malformed one fails the whole request.
     */
    @Test
    fun every_tool_produces_a_definition() {
        val definitions = toolset.definitions()

        assertEquals(toolset.all().size, definitions.size)
        definitions.forEach { definition ->
            assertNotNull(definition.name())
            assertTrue("a tool has no description", definition.description().isPresent)
        }
        assertEquals(
            listOf("list_files", "read_file", "grep", "edit_file"),
            definitions.map { it.name() },
        )
    }

    /**
     * Tool order is a caching property, not a presentation one.
     *
     * Tools render before the system prompt and the messages, so they sit at
     * the very front of the cached prefix. Reordering them invalidates the
     * cache for every conversation, and the only symptom is a larger bill --
     * which is exactly the kind of regression nobody notices.
     */
    @Test
    fun the_tool_order_is_stable_across_instances() {
        val first = ProjectToolset(ProjectFiles(root)).definitions().map { it.name() }
        val second = ProjectToolset(ProjectFiles(root)).definitions().map { it.name() }

        assertEquals(first, second)
    }

    /** The sandbox still applies when the model reaches a tool through the registry. */
    @Test
    fun the_path_guard_still_applies_through_the_toolset() {
        val outcome = toolset.execute(
            "edit_file",
            mapOf("path" to "../escaped.txt", "content" to "no"),
            approved = true,
        )

        assertTrue(outcome is ProjectFiles.Outcome.Refused)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }
}
