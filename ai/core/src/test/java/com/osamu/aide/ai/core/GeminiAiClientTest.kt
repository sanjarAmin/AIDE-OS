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
}
