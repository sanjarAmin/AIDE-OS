package com.osamu.aide.ui.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every level of the file tree indents past its parent.
 *
 * Indentation was capped at six, and `src/main/java/com/example/app/ui` is
 * already seven deep: every file below the cap lined up with its own folder.
 */
class FileTreeIndentTest {

    @Test
    fun every_level_indents_further_than_the_one_above() {
        (1..16).forEach { level ->
            assertTrue("level $level is not right of ${level - 1}", indentDp(level) > indentDp(level - 1))
        }
    }

    /** Past the full steps it narrows, so a deep package keeps its names on a phone. */
    @Test
    fun deep_levels_step_less_than_shallow_ones() {
        assertTrue(indentDp(12) - indentDp(11) < indentDp(2) - indentDp(1))
    }
}
