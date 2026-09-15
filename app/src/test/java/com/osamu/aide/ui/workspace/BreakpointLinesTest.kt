package com.osamu.aide.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BreakpointLinesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val code = (1..10).joinToString("\n") { "line $it" }

    private fun edit(lines: Set<Int>, after: String) = BreakpointLines.followEdit(lines, code, after)

    @Test
    fun a_line_inserted_above_moves_a_breakpoint_down() {
        assertEquals(setOf(6), edit(setOf(5), code.replace("line 2\n", "line 2\nnew\n")))
    }

    @Test
    fun a_line_deleted_above_moves_it_up() {
        assertEquals(setOf(4), edit(setOf(5), code.replace("line 2\n", "")))
    }

    @Test
    fun an_edit_below_leaves_it_alone() {
        assertEquals(setOf(5), edit(setOf(5), code.replace("line 8\n", "line 8\nmore\nand more\n")))
    }

    @Test
    fun typing_on_the_breakpoints_own_line_keeps_it_there() {
        assertEquals(setOf(5), edit(setOf(5), code.replace("line 5", "line 5 // why")))
    }

    @Test
    fun enter_in_the_middle_of_its_line_keeps_it_on_the_first_half() {
        assertEquals(setOf(5, 8), edit(setOf(5, 7), code.replace("line 5", "line\n5")))
    }

    @Test
    fun deleting_its_line_removes_it() {
        // Line 5 is gone, so its breakpoint goes; line 6 is now line 5.
        assertEquals(setOf(5), edit(setOf(5, 6), code.replace("line 5\n", "")))
    }

    @Test
    fun pasting_a_block_moves_everything_after_it() {
        val block = (1..30).joinToString("") { "pasted $it\n" }
        assertEquals(setOf(1, 33, 40), edit(setOf(1, 3, 10), code.replace("line 3\n", block + "line 3\n")))
    }

    @Test
    fun the_store_keeps_them_across_a_restart_relative_to_the_project() {
        val root = temp.newFolder("project")
        val store = BreakpointStore(temp.newFolder("breakpoints"))
        val saved = setOf(
            FileBreakpoint(File(root, "src/main/java/a/Main.java"), 12),
            FileBreakpoint(File(root, "lib/src/main/kotlin/b/Lib.kt"), 3),
        )
        store.save(root, saved)
        assertEquals(saved, store.load(root))
        assertEquals(emptySet<FileBreakpoint>(), store.load(temp.newFolder("other")))
    }
}
