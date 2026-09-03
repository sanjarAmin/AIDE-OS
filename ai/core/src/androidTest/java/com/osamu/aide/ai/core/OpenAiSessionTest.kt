package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The generic loop over OpenAI and everything that speaks its protocol.
 *
 * The shared cases are in [GenericSessionTest]. What is here is the shape only
 * this provider has to get right — and the id round trip, which is the one
 * thing in this file that fails loudly rather than quietly.
 */
@RunWith(AndroidJUnit4::class)
class OpenAiSessionTest : GenericSessionTest() {

    override fun client(api: ScriptedProviderApi): AiClient = api.openAiClient()

    override fun text(text: String) = ScriptedProviderApi.openAiText(text)

    override fun toolCall(vararg calls: ScriptedProviderApi.Call) =
        ScriptedProviderApi.openAiToolCall(*calls)

    override fun assertAssistantTurnReplayed(body: String) {
        assertTrue(
            "the assistant turn was not replayed at all:\n$body",
            "\"role\":\"assistant\"" in body,
        )
        assertTrue(
            "tool_calls was dropped, which makes the tool message an orphan and a 400:\n$body",
            "tool_calls" in body,
        )
    }

    /**
     * OpenAI wants one `tool` message *per* call — not one message holding
     * them all.
     *
     * This is where porting the Anthropic assertion literally would have
     * asserted a bug: "results arrive in a single message" is true of that API
     * and false of this one.
     */
    override fun assertBothResultsReturned(body: String, first: String, second: String) {
        assertEquals(
            "expected one tool message per call:\n$body",
            2,
            body.split("\"role\":\"tool\"").size - 1,
        )
        assertTrue("the result for $first was dropped:\n$body", first in body)
        assertTrue("the result for $second was dropped:\n$body", second in body)
    }

    /**
     * The id on a result is the id the server sent, not one of our own.
     *
     * The Gemini client synthesises a UUID per call, because Gemini has no call
     * ids and matches on the function name. OpenAI does the opposite: it
     * rejects a `tool` message whose `tool_call_id` it does not recognise. A
     * shared [AiPart.FunctionCall] carrying an id makes it easy for one
     * provider's convention to leak into the other, and this is what catches
     * that.
     */
    @Test
    fun a_result_carries_back_the_id_the_server_issued() = runTest {
        session(
            listOf(
                toolCall(
                    ScriptedProviderApi.Call(
                        "call_abc123",
                        "read_file",
                        mapOf("path" to "src/Main.kt"),
                    ),
                ),
                text("done"),
            ),
        ).send("ctx", "read it")

        val second = api!!.body(1)
        assertTrue(
            "the tool result did not carry the server's own call id:\n$second",
            "\"tool_call_id\":\"call_abc123\"" in second,
        )
    }

    /**
     * The route is built, not configured.
     *
     * The base URL a user types is a host — for Ollama, vLLM or OpenRouter as
     * much as for OpenAI — and the client appends `/v1/chat/completions`
     * itself. Getting that wrong is a 404 against every compatible provider at
     * once.
     */
    @Test
    fun the_chat_completions_route_is_appended_to_a_bare_base_url() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        assertEquals("/v1/chat/completions", api!!.path(0))
    }

    /** The key is a bearer token. */
    @Test
    fun the_api_key_is_sent_as_a_bearer_token() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        assertEquals("Bearer test-key", api!!.header(0, "Authorization"))
    }

    /**
     * The system instruction is a message with `role: "system"`, and it comes
     * first.
     *
     * Unlike Gemini and Anthropic, OpenAI has no separate field for it: it is
     * an ordinary message, and a system message that arrives after the user's
     * is just a strangely worded turn.
     */
    @Test
    fun the_system_instruction_is_the_first_message() = runTest {
        session(listOf(text("ok"))).send("PROJECT_CONTEXT_MARKER", "hello")

        val first = api!!.body(0)
        val systemAt = first.indexOf("\"role\":\"system\"")
        val userAt = first.indexOf("\"role\":\"user\"")

        assertTrue("no system message was sent:\n$first", systemAt >= 0)
        assertTrue("the system message came after the user's:\n$first", systemAt < userAt)
        assertTrue("the project context was not in it:\n$first", "PROJECT_CONTEXT_MARKER" in first)
    }

    /** The declarations go in OpenAI's `tools`, each wrapped as a function. */
    @Test
    fun tools_are_declared_in_openais_own_schema() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        val first = api!!.body(0)
        assertTrue("tools were not declared in OpenAI's shape:\n$first", "\"type\":\"function\"" in first)
    }
}
