package com.osamu.aide.ai.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Streaming over the wire, one provider at a time.
 *
 * `StreamAccumulatorTest` covers reassembly; this covers everything around it
 * that a pure accumulator cannot see -- that the request actually asks for a
 * stream, that Gemini's *route* changes rather than its body, that a tool call
 * survives the whole path, and that the deltas arrive in order rather than all
 * at once at the end.
 *
 * **The last one is the point of streaming.** A client that collected the whole
 * body and then replayed it through the callback would pass a test that only
 * checked the final text, and would feel exactly like no streaming at all.
 */
class StreamingClientTest {

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

    private fun sse(vararg events: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")

    private fun openAi(provider: AiProviderType = AiProviderType.OPENAI) = OpenAiClient(
        apiKey = "sk-test",
        customBaseUrl = server.url("/").toString(),
        model = "gpt-4o",
        provider = provider,
    )

    private fun request(tools: List<AideTool> = emptyList()) = AiClientRequest(
        systemInstruction = "system",
        messages = listOf(AiMessage(AiRole.USER, "hello")),
        tools = tools,
    )

    // -- OpenAI --------------------------------------------------------------

    @Test
    fun `deltas arrive separately and the response holds the whole text`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"Hello"}}]}""",
                """{"choices":[{"delta":{"content":", world"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            ),
        )
        val deltas = mutableListOf<String>()

        val response = openAi().send(request()) { deltas += it }

        assertEquals(listOf("Hello", ", world"), deltas)
        assertEquals("Hello, world", response.text)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `the request asks for a stream`() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"x"}}]}"""))

        openAi().send(request()) {}

        val recorded = server.takeRequest()
        assertTrue(JSONObject(recorded.body.readUtf8()).optBoolean("stream"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
    }

    @Test
    fun `the one-shot path still does not ask for a stream`() = runTest {
        // The flag is per-call, not per-client: inline completion and the
        // non-streaming session must keep getting a JSON body.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}"""),
        )

        openAi().send(request())

        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("stream"))
    }

    @Test
    fun `a streamed tool call comes back assembled`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9",""" +
                    """"function":{"name":"read_file","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"a.kt\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
        )

        val response = openAi().send(request()) {}

        val call = response.functionCalls.single()
        assertEquals("read_file", call.name)
        assertEquals("call_9", call.id)
        assertEquals(mapOf("path" to "a.kt"), call.args)
    }

    @Test
    fun `a call written into a streamed reply is still recovered`() = runTest {
        // The §4 regression this exists to prevent: turning streaming on must
        // not take tools away from the local models that write their calls out
        // as prose instead of emitting them.
        val tool = AideTool(
            name = "read_file",
            description = "read a file",
            risk = ToolRisk.READ_ONLY,
            parameters = mapOf("path" to AideTool.Parameter("string", "the path")),
            required = listOf("path"),
        ) { ProjectFiles.Outcome.Refused("not called in this test") }
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"content":"{\"name\": \"read_file\", "}}]}""",
                """{"choices":[{"delta":{"content":"\"arguments\": {\"path\": \"M.kt\"}}"}}]}""",
            ),
        )

        val response = openAi(AiProviderType.LOCAL).send(request(listOf(tool))) {}

        val call = response.functionCalls.single()
        assertEquals("read_file", call.name)
        assertEquals(mapOf("path" to "M.kt"), call.args)
    }

    @Test
    fun `an http failure names the provider the user chose`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val failure = runCatching { openAi(AiProviderType.LOCAL).send(request()) {} }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("On device"))
        assertTrue(failure.message!!.contains("429"))
    }

    // -- Gemini --------------------------------------------------------------

    @Test
    fun `gemini streams from the stream route with alt=sse`() = runTest {
        server.enqueue(sse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""))
        val client = GeminiAiClient(
            apiKey = "k",
            model = "gemini-2.5-flash",
            customEndpoint = server.url("/v1beta/models/gemini-2.5-flash:generateContent").toString(),
        )

        val response = client.send(request()) {}

        val path = server.takeRequest().path!!
        // Both halves matter: the wrong verb returns a 404, and the right verb
        // without alt=sse returns a JSON array that an SSE reader sees as one
        // unparseable blob -- an empty reply with no error anywhere.
        assertTrue("route was $path", path.contains(":streamGenerateContent"))
        assertTrue("route was $path", path.contains("alt=sse"))
        assertEquals("hi", response.text)
    }

    @Test
    fun `gemini thoughts never reach the delta callback`() = runTest {
        server.enqueue(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"pondering","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"the answer"}]}}]}""",
            ),
        )
        val client = GeminiAiClient(
            apiKey = "k",
            model = "gemini-2.5-flash",
            customEndpoint = server.url("/v1beta/models/gemini-2.5-flash:generateContent").toString(),
        )
        val deltas = mutableListOf<String>()

        val response = client.send(request()) { deltas += it }

        assertEquals(listOf("the answer"), deltas)
        assertEquals("the answer", response.text)
    }

    // -- the default ---------------------------------------------------------

    @Test
    fun `a client that does not stream still delivers one delta`() = runTest {
        // The contract that keeps the UI single-pathed: every provider produces
        // deltas, even one whose transport knows nothing about them.
        val plain = object : AiClient {
            override val provider = AiProviderType.CUSTOM
            override val model = "m"
            override suspend fun send(request: AiClientRequest) =
                AiClientResponse(listOf(AiPart.Text("all at once")))
            override suspend fun complete(context: CompletionContext): String? = null
        }
        val deltas = mutableListOf<String>()

        val response = plain.send(request()) { deltas += it }

        assertEquals(listOf("all at once"), deltas)
        assertEquals("all at once", response.text)
    }
}
