package com.osamu.aide.ai.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiAiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun send_parses_text_response() = runTest {
        val jsonResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "Hello from Gemini!" }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = GeminiAiClient(
            apiKey = "test-api-key",
            customEndpoint = server.url("/v1beta/models/gemini-3.7-flash:generateContent").toString(),
        )

        val response = client.send(
            AiClientRequest(
                systemInstruction = "You are a helpful assistant.",
                messages = listOf(AiMessage(AiRole.USER, "Hi")),
            ),
        )

        assertEquals("Hello from Gemini!", response.text)
        assertTrue(response.functionCalls.isEmpty())

        val recorded = server.takeRequest()
        assertEquals("test-api-key", recorded.getHeader("x-goog-api-key"))

        val sentBody = JSONObject(recorded.body.readUtf8())
        assertNotNull(sentBody.optJSONObject("system_instruction"))
        assertEquals(1, sentBody.getJSONArray("contents").length())
    }

    @Test
    fun send_parses_function_call() = runTest {
        val jsonResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "functionCall": {
                          "name": "read_file",
                          "args": { "path": "src/Main.kt" }
                        }
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = GeminiAiClient(
            oauthToken = "ya29.test-bearer-token",
            customEndpoint = server.url("/v1beta/models/gemini-3.7-flash:generateContent").toString(),
        )

        val response = client.send(
            AiClientRequest(
                systemInstruction = "",
                messages = listOf(AiMessage(AiRole.USER, "Read main")),
            ),
        )

        assertEquals(1, response.functionCalls.size)
        val call = response.functionCalls.first()
        assertEquals("read_file", call.name)
        assertEquals("src/Main.kt", call.args["path"])

        val recorded = server.takeRequest()
        assertEquals("Bearer ya29.test-bearer-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun cursor_completion_returns_cleaned_code() = runTest {
        val jsonResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "```kotlin\nprintln(\"hello world\")\n```" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = GeminiAiClient(
            apiKey = "test-api-key",
            customEndpoint = server.url("/v1beta/models/gemini-3.7-flash:generateContent").toString(),
        )

        val completion = client.complete(
            CompletionContext(
                path = "src/Main.kt",
                before = "fun main() {\n    ",
                after = "\n}",
            ),
        )

        assertEquals("println(\"hello world\")", completion)
    }

    /**
     * Every model in the picker is classified for thinking, one way or other.
     *
     * The rule used to be `model.contains("3.7") || model.contains("flash")`,
     * so adding a model to the picker silently decided its thinking behaviour
     * by whether its name happened to contain a substring — and a `gemini-4-pro`
     * would have lost its budget with nothing to notice. This fails instead,
     * at build time, naming the model that needs a decision.
     */
    @Test
    fun every_offered_gemini_model_is_classified_for_thinking() {
        val classified = GeminiAiClient.THINKING_MODELS + GeminiAiClient.NON_THINKING_MODELS
        val unclassified = AiProviderType.GEMINI.availableModels - classified

        assertEquals(
            "these models are offered but not classified for thinkingConfig — " +
                "add each to THINKING_MODELS or NON_THINKING_MODELS in GeminiAiClient",
            emptyList<String>(),
            unclassified,
        )
    }

    /** And nothing is classified that is not offered, so the sets do not rot. */
    @Test
    fun nothing_is_classified_that_the_picker_no_longer_offers() {
        val classified = GeminiAiClient.THINKING_MODELS + GeminiAiClient.NON_THINKING_MODELS
        val stale = classified - AiProviderType.GEMINI.availableModels.toSet()

        assertEquals("classified models the picker no longer offers", emptySet<String>(), stale)
    }
}
