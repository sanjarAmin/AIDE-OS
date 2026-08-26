package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inline completion: the request it sends, and what it does with the reply.
 *
 * The cleanup is the half that bites. The model is asked for bare code and
 * mostly complies, and "mostly" inserted straight into a buffer means a stray
 * fence somewhere in the middle of the user's file -- a mess they have to find
 * and delete, with no error anywhere.
 */
@RunWith(AndroidJUnit4::class)
class InlineCompleterTest {

    private var api: ScriptedApi? = null

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @After
    fun tearDown() {
        api?.stop()
    }

    private fun completer(reply: String): InlineCompleter {
        val scripted = ScriptedApi(listOf(ScriptedApi.text(reply))).also { api = it }
        return InlineCompleter(scripted.client(), dispatchers)
    }

    private val context = CompletionContext(
        path = "src/Main.kt",
        before = "fun main() {\n    val name = \"world\"\n    ",
        after = "\n}\n",
    )

    @Test
    fun a_completion_comes_back_ready_to_insert() = runTest {
        val text = completer("println(\"Hello, \$name\")").complete(context)

        assertEquals("println(\"Hello, \$name\")", text)
    }

    /**
     * The request must carry no tools and cost little.
     *
     * A completion that stopped to read three files would arrive after the user
     * had typed the line themselves, and effort is the plan's per-feature cost
     * lever -- this is the feature at the cheap end of it.
     */
    @Test
    fun the_request_is_cheap_and_toolless() = runTest {
        completer("x").complete(context)

        val body = api!!.body(0)
        assertFalse("completion must not offer tools:\n$body", "\"tools\"" in body)
        assertTrue(body, "\"effort\":\"low\"" in body)
        assertTrue("the ceiling is what stops it writing the class", "\"max_tokens\":256" in body)
    }

    @Test
    fun both_sides_of_the_cursor_are_sent() = runTest {
        completer("x").complete(context)

        val body = api!!.body(0)
        assertTrue(body, "before_cursor" in body)
        assertTrue(body, "after_cursor" in body)
        assertTrue("the file's name tells the model the language", "src/Main.kt" in body)
    }

    /**
     * A whole file per request is the cost mistake this feature invites, since
     * it is asked for far more often than chat is.
     */
    @Test
    fun a_huge_file_is_windowed_rather_than_sent_whole() = runTest {
        val enormous = CompletionContext(
            path = "src/Big.kt",
            before = "// leading junk\n".repeat(2_000) + "val marker = 1\n",
            after = "x".repeat(20_000),
        )

        completer("y").complete(enormous)

        val body = api!!.body(0)
        assertTrue("the request grew with the file: ${body.length} chars", body.length < 12_000)
        assertTrue("the text nearest the cursor must survive the window", "val marker = 1" in body)
    }

    /** "Nothing to add here" is a real answer, and must not read as success. */
    @Test
    fun an_empty_reply_becomes_no_suggestion() = runTest {
        assertNull(completer("   \n  ").complete(context))
    }

    // -- cleanup ------------------------------------------------------------

    @Test
    fun a_fenced_reply_is_unwrapped() {
        assertEquals(
            "println(42)",
            cleanCompletion("```kotlin\nprintln(42)\n```"),
        )
    }

    /** When the model fences, whatever is outside the fence is the prose. */
    @Test
    fun prose_around_a_fence_is_discarded() {
        val raw = "Here is the completion:\n\n```java\nSystem.out.println(1);\n```\n\nHope that helps!"

        assertEquals("System.out.println(1);", cleanCompletion(raw))
    }

    /**
     * The token ceiling can cut a reply mid-fence. Everything after the opening
     * fence is still code, and dropping it would turn a usable completion into
     * no completion.
     */
    @Test
    fun an_unterminated_fence_keeps_what_came_after_it() {
        assertEquals(
            "val a = 1\nval b = 2",
            cleanCompletion("```kotlin\nval a = 1\nval b = 2"),
        )
    }

    /**
     * Leading spaces survive; leading newlines do not.
     *
     * The cursor is already at some indentation, and trimming the model's
     * leading spaces would land the continuation in column zero.
     */
    @Test
    fun leading_indentation_is_preserved_but_blank_lines_are_not() {
        assertEquals("    return x", cleanCompletion("\n\n    return x\n"))
    }

    @Test
    fun a_reply_that_is_only_a_fence_yields_nothing() {
        assertEquals("", cleanCompletion("```\n\n```"))
    }
}
