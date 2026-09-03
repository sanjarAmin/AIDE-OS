package com.osamu.aide.ai.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {

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
    fun send_parses_choices_and_content() = runTest {
        val jsonResponse = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Hello from OpenAI!"
                  },
                  "finish_reason": "stop"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = OpenAiClient(
            apiKey = "sk-test",
            customBaseUrl = server.url("/").toString(),
            model = "gpt-4o",
        )

        val response = client.send(
            AiClientRequest(
                systemInstruction = "System prompt",
                messages = listOf(AiMessage(AiRole.USER, "Hello")),
            ),
        )

        assertEquals("Hello from OpenAI!", response.text)
        assertTrue(response.functionCalls.isEmpty())

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))

        val sentBody = JSONObject(recorded.body.readUtf8())
        assertEquals("gpt-4o", sentBody.getString("model"))
        assertEquals(2, sentBody.getJSONArray("messages").length())
    }

    @Test
    fun send_parses_tool_calls() = runTest {
        val jsonResponse = """
            {
              "id": "chatcmpl-456",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_abc123",
                        "type": "function",
                        "function": {
                          "name": "edit_file",
                          "arguments": "{\"path\":\"src/Main.kt\",\"content\":\"fun main() = Unit\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(jsonResponse))

        val client = OpenAiClient(
            apiKey = "sk-test",
            customBaseUrl = server.url("/").toString(),
        )

        val response = client.send(
            AiClientRequest(
                systemInstruction = "",
                messages = listOf(AiMessage(AiRole.USER, "Write main")),
            ),
        )

        assertEquals(1, response.functionCalls.size)
        val call = response.functionCalls.first()
        assertEquals("call_abc123", call.id)
        assertEquals("edit_file", call.name)
        assertEquals("src/Main.kt", call.args["path"])
        assertEquals("fun main() = Unit", call.args["content"])
    }
}
