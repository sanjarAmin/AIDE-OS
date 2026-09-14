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

    /**
     * Custom with no address sends nothing -- and certainly not to OpenAI.
     *
     * It fell back to api.openai.com, carrying whatever key was saved under
     * Custom. Found on a phone as OpenAI's 404 for `llama3.3:70b`. Asserted
     * here by giving the client no address and checking the refusal names the
     * fix; there is no server to have received anything.
     */
    @Test
    fun a_custom_client_with_no_address_refuses_rather_than_calling_openai() = runTest {
        val client = OpenAiClient(
            apiKey = "gsk-a-key-for-some-other-service",
            customBaseUrl = null,
            model = "llama3.3:70b",
            provider = AiProviderType.CUSTOM,
        )
        val failure = runCatching {
            client.send(AiClientRequest(systemInstruction = "", messages = listOf(AiMessage(AiRole.USER, "Hi"))))
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalStateException)
        assertTrue(
            "the refusal does not say where to fix it: ${failure?.message}",
            failure!!.message!!.contains("Settings"),
        )
        assertEquals("the request was sent anyway", 0, server.requestCount)
    }

    /** A completion from an addressless Custom client is simply nothing. */
    @Test
    fun a_custom_client_with_no_address_completes_nothing() = runTest {
        val client = OpenAiClient(customBaseUrl = null, provider = AiProviderType.CUSTOM)
        assertEquals(null, client.complete(CompletionContext(path = "A.kt", before = "fun a() {", after = "}")))
    }

    /** A failure names the provider the user chose, not the protocol it speaks. */
    @Test
    fun a_failure_names_the_provider_that_failed() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model_not_found"}"""))
        val client = OpenAiClient(
            customBaseUrl = server.url("/").toString(),
            model = "llama3.3:70b",
            provider = AiProviderType.CUSTOM,
        )
        val failure = runCatching {
            client.send(AiClientRequest(systemInstruction = "", messages = listOf(AiMessage(AiRole.USER, "Hi"))))
        }.exceptionOrNull()
        assertTrue("wrong provider named: ${failure?.message}", failure!!.message!!.startsWith("Custom request failed (404)"))
    }
}
