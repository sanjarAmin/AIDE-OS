package com.osamu.aide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the editor paints after a file changes underneath it.
 *
 * The cases that matter are the ones an assistant's edit actually produces:
 * one line rewritten in the middle, a block inserted, a file replaced whole,
 * and -- the one worth being careful about -- a write that changed nothing.
 */
class ChangedLinesTest {

    private val original = "package a\n\nfun main() {\n    println(1)\n}\n"

    @Test
    fun `an unchanged file highlights nothing`() {
        assertEquals(emptySet<Int>(), changedLines(original, original))
        assertEquals(emptySet<Int>(), changedLines("", ""))
    }

    @Test
    fun `one rewritten line is the only one marked`() {
        val after = original.replace("println(1)", "println(42)")

        assertEquals(setOf(4), changedLines(original, after))
    }

    @Test
    fun `an inserted block is marked and its neighbours are not`() {
        val after = original.replace(
            "    println(1)\n",
            "    println(1)\n    println(2)\n    println(3)\n",
        )

        assertEquals(setOf(5, 6), changedLines(original, after))
    }

    @Test
    fun `a file written from nothing marks every line`() {
        assertEquals(setOf(1, 2, 3), changedLines("", "a\nb\nc"))
    }

    @Test
    fun `a deletion marks nothing, because nothing new is on screen`() {
        // Pointing at the line that closed the gap would name a line the user
        // did not change.
        val after = original.replace("    println(1)\n", "")

        assertEquals(emptySet<Int>(), changedLines(original, after))
    }

    @Test
    fun `a changed first line does not swallow the rest`() {
        assertEquals(setOf(1), changedLines("a\nb\nc", "A\nb\nc"))
    }

    @Test
    fun `a changed last line does not swallow the rest`() {
        assertEquals(setOf(3), changedLines("a\nb\nc", "a\nb\nC"))
    }

    @Test
    fun `repeated lines do not confuse the head and tail scan`() {
        // The tail scan must not walk back past the head and count a line
        // twice, which is what an unguarded loop does on a file of one
        // repeated line.
        assertEquals(setOf(2), changedLines("x\nx\nx", "x\ny\nx"))
        assertEquals(setOf(3), changedLines("x\nx", "x\nx\nx"))
    }
}
