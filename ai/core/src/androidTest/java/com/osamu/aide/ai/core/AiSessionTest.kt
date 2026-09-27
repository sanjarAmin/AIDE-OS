package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The tool loop.
 *
 * Every test here goes through the real SDK against a local Messages API rather
 * than around it, because the things that break in a tool loop are all on the
 * wire: which blocks the second request carries, whether every `tool_use` got a
 * `tool_result`, whether they arrived in one message. A test that inspected the
 * session's own list instead would pass while the API rejected the request.
 */
@RunWith(AndroidJUnit4::class)
class AiSessionTest {

    private lateinit var root: File
    private lateinit var files: ProjectFiles
    private lateinit var toolset: ProjectToolset
    private var api: ScriptedApi? = null

    /** Everything on the caller's thread: `runTest` then controls the clock. */

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "session-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }
            .writeText("fun main() = println(\"hi\")")
        files = ProjectFiles(root)
        toolset = ProjectToolset(files)
    }

    @After
    fun tearDown() {
        api?.stop()
        root.deleteRecursively()
    }

    private fun session(
        responses: List<String>,
        approver: Approver = Approver { _, _ -> true },
        maxToolRounds: Int = 12,
    ): AiSession {
        val scripted = ScriptedApi(responses).also { api = it }
        return AiSession(
            client = scripted.client(),
            assembler = PromptAssembler(toolset),
            toolset = toolset,
            approver = approver,
            dispatchers = unconfinedDispatchers,
            maxToolRounds = maxToolRounds,
        )
    }

    @Test
    fun a_plain_answer_needs_one_request() = runTest {
        val reply = session(listOf(ScriptedApi.text("Nothing to do."))).send("ctx", "hello")

        assertEquals("Nothing to do.", reply.text)
        assertTrue(reply.toolRuns.isEmpty())
        assertFalse(reply.truncated)
        assertEquals(1, api!!.requestCount)
    }

    @Test
    fun a_tool_call_is_run_and_its_output_returned_to_the_model() = runTest {
        val reply = session(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "read_file", """{"path":"src/Main.kt"}"""),
                ),
                ScriptedApi.text("It prints hi."),
            ),
        ).send("ctx", "what does Main.kt do?")

        assertEquals("It prints hi.", reply.text)
        assertEquals(listOf("read_file"), reply.toolRuns.map { it.name })
        assertEquals(2, api!!.requestCount)

        val second = api!!.body(1)
        val result = toolResults(second).single()
        assertEquals("the result answers the wrong call:\n$second", "tu_1", result.id)
        assertTrue("the file's contents were not sent back:\n$second", "println" in result.content)
    }

    /**
     * Detail 1 of the loop, and the one with no local symptom.
     *
     * Thinking is adaptive, so real responses carry thinking blocks, and the API
     * requires them back unchanged. A session that rebuilt the assistant turn
     * from its text would pass every other test here and fail against the real
     * endpoint on the second request.
     */
    @Test
    fun the_assistant_turn_is_replayed_with_its_thinking_block_intact() = runTest {
        session(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "list_files", "{}"),
                ),
                ScriptedApi.text("done"),
            ),
        ).send("ctx", "what is in here?")

        val second = api!!.body(1)
        // Inside the replayed assistant turn. `"thinking"` alone also matches
        // the request's own adaptive-thinking setting, so it could not fail.
        val thinking = messages(second).filter { it.optString("role") == "assistant" }
            .flatMap(::blocks)
            .filter { it.optString("type") == "thinking" }
        assertEquals("the thinking block was dropped from the replayed turn:\n$second", 1, thinking.size)
        assertEquals("the thinking block's signature was not replayed", "sig_test", thinking.single().optString("signature"))
    }

    /**
     * Detail 2. Splitting parallel results across messages has no error and no
     * wrong output -- the model just stops asking for parallel calls, and the
     * assistant quietly gets slower.
     */
    @Test
    fun parallel_tool_results_come_back_in_a_single_user_message() = runTest {
        val reply = session(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "read_file", """{"path":"src/Main.kt"}"""),
                    ScriptedApi.Companion.Call("tu_2", "grep", """{"query":"fun"}"""),
                ),
                ScriptedApi.text("both read"),
            ),
        ).send("ctx", "read and search")

        assertEquals(listOf("read_file", "grep"), reply.toolRuns.map { it.name })
        assertEquals(2, api!!.requestCount)

        // Three messages, not four: user, assistant, and one user turn holding
        // both results.
        val second = api!!.body(1)
        val turns = messages(second)
        assertEquals("expected user, assistant, and one turn of results:\n$second", 3, turns.size)
        assertEquals(
            "the two tool results were not sent in one message:\n$second",
            listOf("tu_1", "tu_2"),
            blocks(turns.last()).filter { it.optString("type") == "tool_result" }.map { it.optString("tool_use_id") },
        )
    }

    /** Detail 3: a refusal is still a result, or the next request is a 400. */
    @Test
    fun a_declined_edit_is_reported_to_the_model_rather_than_dropped() = runTest {
        val reply = session(
            responses = listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call(
                        "tu_1",
                        "edit_file",
                        """{"path":"src/Main.kt","content":"fun main() = Unit"}""",
                    ),
                ),
                ScriptedApi.text("Understood, leaving it alone."),
            ),
            approver = Approver { _, _ -> false },
        ).send("ctx", "empty out Main.kt")

        assertFalse("the edit should not have been approved", reply.toolRuns.single().approved)
        assertEquals(
            "the file was written despite the refusal",
            "fun main() = println(\"hi\")",
            File(root, "src/Main.kt").readText(),
        )

        val second = api!!.body(1)
        val result = toolResults(second).single()
        assertEquals("no tool_result was sent for the declined call:\n$second", "tu_1", result.id)
        assertTrue("the refusal was not flagged as an error:\n$second", result.isError)
    }

    /** And approval really does let the write through. */
    @Test
    fun an_approved_edit_reaches_the_project() = runTest {
        session(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call(
                        "tu_1",
                        "edit_file",
                        """{"path":"src/New.kt","content":"val answer = 42"}""",
                    ),
                ),
                ScriptedApi.text("Written."),
            ),
        ).send("ctx", "add New.kt")

        assertEquals("val answer = 42", File(root, "src/New.kt").readText())
    }

    /**
     * The approver is asked only about tools that can change something.
     *
     * A prompt on every read would train the user to tap "allow" without
     * looking, which is how the one prompt that mattered gets approved.
     */
    @Test
    fun read_only_tools_do_not_prompt() = runTest {
        val asked = mutableListOf<String>()

        session(
            responses = listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "list_files", "{}"),
                ),
                ScriptedApi.text("done"),
            ),
            approver = Approver { name, _ -> asked += name; true },
        ).send("ctx", "look around")

        assertTrue("a read-only tool asked for confirmation: $asked", asked.isEmpty())
    }

    /**
     * The runaway guard.
     *
     * A model that keeps calling tools spends the user's money one plausible
     * step at a time, and every round looks like progress from the outside.
     * The cap ends the turn with the history still valid, so "carry on" works.
     */
    @Test
    fun a_model_that_never_stops_calling_tools_is_cut_off() = runTest {
        val reply = session(
            responses = listOf(
                ScriptedApi.toolUse(ScriptedApi.Companion.Call("tu_1", "list_files", "{}")),
            ),
            maxToolRounds = 3,
        ).send("ctx", "go")

        assertTrue("the loop should have reported being cut short", reply.truncated)
        assertEquals(3, api!!.requestCount)
        assertEquals(3, reply.toolRuns.size)
    }

    /** History survives across turns, which is what makes it a conversation. */
    @Test
    fun the_conversation_accumulates_across_turns() = runTest {
        val session = session(listOf(ScriptedApi.text("first"), ScriptedApi.text("second")))

        session.send("ctx", "one")
        session.send("ctx", "two")

        assertEquals(4, session.history.size)
        val second = api!!.body(1)
        // The user turns themselves. "one" alone matched read_file's own
        // description, "Read one file in full", in every request.
        val userTexts = messages(second).filter { it.optString("role") == "user" }.map(::textOf)
        assertEquals("the first turn was not carried forward:\n$second", listOf("one", "two"), userTexts)
    }

    // -- reading a request body ---------------------------------------------
    //
    // Parsed rather than searched: a request also carries every tool's
    // declaration, the system text and the replayed calls, so a substring of
    // the body is found whether or not the thing asserted was sent.

    private class Result(val id: String, val content: String, val isError: Boolean)

    private fun messages(body: String): List<JSONObject> {
        val array = JSONObject(body).getJSONArray("messages")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    /** A message's content blocks; a plain string content has none. */
    private fun blocks(message: JSONObject): List<JSONObject> {
        val content = message.optJSONArray("content") ?: return emptyList()
        return (0 until content.length()).map { content.getJSONObject(it) }
    }

    private fun textOf(message: JSONObject): String =
        message.optJSONArray("content")?.let { _ ->
            blocks(message).filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        } ?: message.optString("content")

    private fun toolResults(body: String): List<Result> = messages(body)
        .flatMap(::blocks)
        .filter { it.optString("type") == "tool_result" }
        .map { block ->
            val content = block.optJSONArray("content")?.let { parts ->
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            } ?: block.optString("content")
            Result(block.optString("tool_use_id"), content, block.optBoolean("is_error"))
        }
}
