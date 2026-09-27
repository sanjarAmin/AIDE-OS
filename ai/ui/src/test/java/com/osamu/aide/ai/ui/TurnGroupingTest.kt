package com.osamu.aide.ai.ui

import com.osamu.aide.ai.core.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a flat list of entries becomes the turns the rail draws.
 *
 * The controller appends entries one at a time and knows nothing about turns;
 * the rail is the structure a reader sees, so the grouping is where the two
 * meet -- and a mistake here misattributes work to the wrong question, which is
 * the one thing an audit trail must never do.
 */
class TurnGroupingTest {

    private fun user(text: String) = ChatEntry.FromUser(text)
    private fun assistant(text: String, streaming: Boolean = false) =
        ChatEntry.FromAssistant(text, streaming = streaming)

    private fun tool(name: String, failed: Boolean = false) =
        ChatEntry.Tool(name, "x", declined = false, failed = failed)

    @Test
    fun `prose, a tool and more prose are one answer`() {
        val turns = groupTurns(
            listOf(user("q"), assistant("looking"), tool("read_file"), assistant("found it")),
        )

        assertEquals(2, turns.size)
        assertTrue(turns[0] is Turn.Question)
        val answer = turns[1] as Turn.Answer
        assertEquals(3, answer.entries.size)
    }

    @Test
    fun `each question starts a new turn`() {
        val turns = groupTurns(
            listOf(user("one"), assistant("a"), user("two"), assistant("b")),
        )

        assertEquals(4, turns.size)
        assertEquals(listOf(true, false, true, false), turns.map { it is Turn.Question })
    }

    @Test
    fun `an answer with no question of its own still groups`() {
        // The shape a reopened conversation can have if it was cancelled mid
        // turn, and the shape of the very first entry when a tool runs before
        // any prose arrives.
        val turns = groupTurns(listOf(tool("list_files"), assistant("done")))

        assertEquals(1, turns.size)
        assertEquals(2, (turns.single() as Turn.Answer).entries.size)
    }

    @Test
    fun `an empty transcript has no turns`() {
        assertTrue(groupTurns(emptyList()).isEmpty())
    }

    @Test
    fun `the key is stable and unique per turn`() {
        // A LazyColumn throws on duplicate keys, and a key that changes per
        // token rebuilds the item and loses its expanded tool cards.
        val entries = listOf(user("q"), assistant("a"), tool("read_file"), user("q2"), assistant("b"))
        val keys = groupTurns(entries).map { it.key }

        assertEquals(keys.size, keys.distinct().size)
        assertEquals(keys, groupTurns(entries).map { it.key })
    }

    @Test
    fun `a turn is streaming while any of its prose is`() {
        val turn = groupTurns(listOf(assistant("half", streaming = true))).single() as Turn.Answer

        assertTrue(turn.streaming)
    }

    @Test
    fun `a turn with a failed tool is marked failed`() {
        val good = groupTurns(listOf(tool("read_file"))).single() as Turn.Answer
        val bad = groupTurns(listOf(tool("read_file", failed = true))).single() as Turn.Answer

        assertFalse(good.failed)
        assertTrue(bad.failed)
    }

    @Test
    fun `copying an answer takes the prose and not the tool output`() {
        // The tool result is verbatim machine output, often hundreds of lines.
        // Copy means "give me the answer", so it must not include it.
        val turn = groupTurns(
            listOf(
                assistant("First part."),
                ChatEntry.Tool("read_file", "M.kt", declined = false, failed = false, result = "SHOULD NOT APPEAR"),
                assistant("Second part."),
            ),
        ).single() as Turn.Answer

        assertEquals("First part.\n\nSecond part.", turn.prose)
    }

    @Test
    fun `the attached file marker is read back off a question`() {
        assertEquals("Main.kt", attachedFile("[@Main.kt]\nexplain this"))
        assertEquals("explain this", withoutMarker("[@Main.kt]\nexplain this"))
        assertEquals(null, attachedFile("no marker here"))
        assertEquals("no marker here", withoutMarker("no marker here"))
    }
}
