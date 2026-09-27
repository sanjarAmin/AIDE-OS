package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The generic loop over Gemini, plus what is specific to Gemini's wire format.
 *
 * The shared cases are in [GenericSessionTest]; what is here is the shape only
 * this provider has to get right.
 */
@RunWith(AndroidJUnit4::class)
class GeminiSessionTest : GenericSessionTest() {

    override fun client(api: ScriptedProviderApi): AiClient = api.geminiClient()

    override fun text(text: String) = ScriptedProviderApi.geminiText(text)

    override fun toolCall(vararg calls: ScriptedProviderApi.Call) =
        ScriptedProviderApi.geminiToolCall(*calls)

    override fun assertAssistantTurnReplayed(body: String) {
        assertTrue("the model's turn was not replayed at all:\n$body", "\"role\":\"model\"" in body)
        assertTrue(
            "the functionCall was dropped, leaving its response an orphan:\n$body",
            "functionCall" in body,
        )
    }

    /**
     * Gemini takes every result in one `user` turn, as parts.
     *
     * The same rule the Anthropic loop has, and it lands the same way: split
     * across turns there is no error, the model just stops asking for parallel
     * calls and the assistant gets slower for no visible reason.
     */
    override fun assertBothResultsReturned(body: String, first: String, second: String) {
        assertEquals(
            "the two results were not sent in one user turn:\n$body",
            2,
            body.split("\"role\":\"user\"").size - 1,
        )
        assertEquals(
            "expected exactly two functionResponse parts:\n$body",
            2,
            body.split("functionResponse").size - 1,
        )
    }

    /**
     * A refusal is flagged, not just described.
     *
     * The reason text alone reads as a tool that ran and returned prose. Gemini
     * has no `is_error` of its own on a `functionResponse`, so the client puts
     * one inside the response object; without it a declined edit looks to the
     * model like a successful one.
     */
    @Test
    fun a_refusal_is_marked_as_an_error_and_not_just_narrated() = runTest {
        session(
            responses = listOf(
                toolCall(
                    ScriptedProviderApi.Call(
                        "call_1",
                        "edit_file",
                        mapOf("path" to "src/Main.kt", "content" to "x"),
                    ),
                ),
                text("ok"),
            ),
            approver = Approver { _, _ -> false },
        ).send("ctx", "edit it")

        val second = api!!.body(1)
        assertTrue("the refusal was not flagged:\n$second", "\"is_error\":true" in second)
    }

    /** An API key goes in Gemini's own header, not as a bearer token. */
    @Test
    fun an_api_key_is_sent_as_the_google_api_key_header() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        assertEquals("test-key", api!!.header(0, "x-goog-api-key"))
        assertNull("an API key must not be sent as a bearer token", api!!.header(0, "Authorization"))
    }

    /**
     * An OAuth access token goes in `Authorization`, and takes precedence.
     *
     * Both credentials can be stored at once — the settings screen lets a user
     * sign in *and* paste a key — so which one wins is a decision, and sending
     * the wrong one is a 401 the user cannot explain.
     */
    @Test
    fun an_oauth_token_is_sent_as_a_bearer_token_and_wins_over_a_key() = runTest {
        val scripted = ScriptedProviderApi(listOf(text("ok"))).also { api = it }
        AiSession(
            aiClient = scripted.geminiClient(apiKey = "test-key", oauthToken = "test-token"),
            toolset = toolset,
            approver = Approver { _, _ -> true },
            dispatchers = unconfined,
        ).send("ctx", "hello")

        assertEquals("Bearer test-token", scripted.header(0, "Authorization"))
        assertNull(
            "the API key was sent alongside the OAuth token",
            scripted.header(0, "x-goog-api-key"),
        )
    }

    /** The declarations go in Gemini's `function_declarations`, not OpenAI's shape. */
    @Test
    fun tools_are_declared_in_geminis_own_schema() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        val first = api!!.body(0)
        assertTrue("tools were not declared in Gemini's shape:\n$first", "function_declarations" in first)
        assertTrue("the system instruction was not sent in Gemini's shape:\n$first", "system_instruction" in first)
    }
}
