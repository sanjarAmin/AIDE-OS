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

    // ---- Calls a local model wrote out instead of making ----

    private fun readFileTool() = AideTool(
        name = "read_file",
        description = "Read one file in the project.",
        risk = ToolRisk.READ_ONLY,
        parameters = mapOf("path" to AideTool.Parameter("string", "Path to read.")),
        required = listOf("path"),
        handler = { ProjectFiles.Outcome.Ok("never executed in this test") },
    )

    private fun replyWith(content: String) = """
        {"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(content)}},
         "finish_reason":"stop"}]}
    """.trimIndent()

    private suspend fun sendWithTool(content: String, tools: List<AideTool>) : AiClientResponse {
        server.enqueue(MockResponse().setResponseCode(200).setBody(replyWith(content)))
        return OpenAiClient(
            apiKey = "sk-test",
            customBaseUrl = server.url("/").toString(),
            model = "local",
        ).send(
            AiClientRequest(
                systemInstruction = "s",
                messages = listOf(AiMessage(AiRole.USER, "Read src/main/Main.kt")),
                tools = tools,
            ),
        )
    }

    /**
     * **The case this exists for.** A local model picks the right tool and then
     * prints it as JSON, because the server's parser never recognised the
     * markup its chat template emitted -- Qwen2.5-Coder 1.5B did it 5/5 and the
     * 7B 3/3 with `--jinja` already on. `tools/localai/FINDINGS.md` §4.
     */
    @Test
    fun a_call_written_as_json_in_the_reply_is_recovered() = runTest {
        val response = sendWithTool(
            "I'll read it.\n```json\n{\"name\": \"read_file\", " +
                "\"arguments\": {\"path\": \"src/main/Main.kt\"}}\n```",
            listOf(readFileTool()),
        )

        val call = response.parts.filterIsInstance<AiPart.FunctionCall>().single()
        assertEquals("read_file", call.name)
        assertEquals("src/main/Main.kt", call.args["path"])
        // The prose survives; the mechanics of the call do not.
        val text = response.parts.filterIsInstance<AiPart.Text>().joinToString("") { it.text }
        assertEquals("I'll read it.", text)
        assertTrue("the raw JSON was shown to the user: $text", "read_file" !in text)
    }

    /** OpenAI's own shape, where `arguments` is a string rather than an object. */
    @Test
    fun a_written_call_whose_arguments_are_a_string_is_recovered() = runTest {
        val response = sendWithTool(
            "{\"name\": \"read_file\", \"arguments\": \"{\\\"path\\\": \\\"a/b.kt\\\"}\"}",
            listOf(readFileTool()),
        )

        val call = response.parts.filterIsInstance<AiPart.FunctionCall>().single()
        assertEquals("a/b.kt", call.args["path"])
    }

    /**
     * **The dangerous direction, and the one worth most.**
     *
     * An IDE assistant is asked to *show* JSON constantly. Recovering "a reply
     * containing an object" would run tools the user only asked to look at, and
     * the damage is done before anything is displayed. The guard is that the
     * object must name a tool this request offered.
     */
    @Test
    fun an_ordinary_json_answer_is_not_mistaken_for_a_call() = runTest {
        val answer = "Here is a package.json:\n```json\n" +
            "{\"name\": \"my-app\", \"version\": \"1.0.0\", \"arguments\": {\"path\": \"x\"}}\n```"

        val response = sendWithTool(answer, listOf(readFileTool()))

        assertTrue(
            "a JSON answer was executed as a tool call",
            response.parts.none { it is AiPart.FunctionCall },
        )
        assertTrue("the answer was not shown", response.text.contains("my-app"))
    }

    /** A request that offered no tools can never recover one. */
    @Test
    fun a_reply_is_never_a_call_when_no_tools_were_offered() = runTest {
        val response = sendWithTool(
            "{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.kt\"}}",
            emptyList(),
        )

        assertTrue(
            "a call was recovered although the request declared no tools",
            response.parts.none { it is AiPart.FunctionCall },
        )
    }

    /** A structured call wins; the text is not searched at all. */
    @Test
    fun a_structured_call_is_not_joined_by_a_recovered_one() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"choices":[{"message":{"role":"assistant",
                 "content":"{\"name\": \"read_file\", \"arguments\": {\"path\": \"text.kt\"}}",
                 "tool_calls":[{"id":"call_1","type":"function","function":
                   {"name":"read_file","arguments":"{\"path\": \"structured.kt\"}"}}]},
                 "finish_reason":"tool_calls"}]}
                """.trimIndent(),
            ),
        )
        val response = OpenAiClient(
            apiKey = "sk-test",
            customBaseUrl = server.url("/").toString(),
            model = "local",
        ).send(
            AiClientRequest(
                systemInstruction = "s",
                messages = listOf(AiMessage(AiRole.USER, "read it")),
                tools = listOf(readFileTool()),
            ),
        )

        val calls = response.parts.filterIsInstance<AiPart.FunctionCall>()
        assertEquals("the written copy was recovered as a second call", 1, calls.size)
        assertEquals("structured.kt", calls.single().args["path"])
    }
}
