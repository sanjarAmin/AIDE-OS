package com.osamu.aide.ai.core

import com.osamu.aide.ai.core.DiffLine.Kind.ADDED
import com.osamu.aide.ai.core.DiffLine.Kind.CONTEXT
import com.osamu.aide.ai.core.DiffLine.Kind.GAP
import com.osamu.aide.ai.core.DiffLine.Kind.REMOVED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {

    /** Rows as short strings: `+3 text`, `-  text`, ` 4 text`, `~ note`. */
    private fun render(rows: List<DiffLine>): List<String> = rows.map {
        when (it.kind) {
            ADDED -> "+${it.number} ${it.text}"
            REMOVED -> "- ${it.text}"
            CONTEXT -> " ${it.number} ${it.text}"
            GAP -> "~ ${it.text}"
        }
    }

    @Test
    fun `a line beginning with a minus is not a removal`() {
        // The bug this replaced: the prompt was given the new file and read a
        // leading `-` as "deleted". A YAML list, unchanged, showed as removed.
        val old = "items:\n- one\n- two\n"
        val new = "items:\n- one\n- two\n- three\n"

        val rows = LineDiff.of(old, new)

        assertEquals(listOf(" 1 items:", " 2 - one", " 3 - two", "+4 - three"), render(rows))
    }

    @Test
    fun `an unchanged file is no rows at all`() {
        assertTrue(LineDiff.of("a\nb\n", "a\nb\n").isEmpty())
    }

    @Test
    fun `a new file is all additions, numbered`() {
        assertEquals(listOf("+1 a", "+2 b"), render(LineDiff.of(null, "a\nb")))
    }

    @Test
    fun `a change in a long file shows what goes, what replaces it, and where`() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 10", "line ten")

        assertEquals(
            listOf(
                "~ 6 unchanged lines",
                " 7 line 7", " 8 line 8", " 9 line 9",
                "- line 10",
                "+10 line ten",
                " 11 line 11", " 12 line 12", " 13 line 13",
                "~ 7 unchanged lines",
            ),
            render(LineDiff.of(old, new)),
        )
    }

    @Test
    fun `numbers follow the new file across a deletion`() {
        val rows = LineDiff.of("a\nb\nc\nd\n", "a\nc\nd\n")
        assertEquals(listOf(" 1 a", "- b", " 2 c", " 3 d"), render(rows))
    }

    @Test
    fun `lines moved apart in the middle are matched, not replaced wholesale`() {
        val rows = LineDiff.of("x\nkeep\ny\n", "p\nkeep\nq\n")
        assertEquals(listOf("- x", "+1 p", " 2 keep", "- y", "+3 q"), render(rows))
    }

    @Test
    fun `windows line endings do not make every line differ`() {
        assertTrue(LineDiff.of("a\r\nb\r\n", "a\nb\n").isEmpty())
    }

    @Test
    fun `a huge rewrite is capped rather than drawn in full`() {
        val new = (1..1_000).joinToString("\n") { "n$it" }
        val rows = LineDiff.of(null, new, maxRows = 50)
        assertEquals(51, rows.size)
        assertEquals("~ 950 more lines not shown", render(rows).last())
    }
}
