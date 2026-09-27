package com.osamu.aide.ai.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A Gemini or OpenAI-compatible endpoint that answers from a script.
 *
 * The sibling of [ScriptedApi], for the *other* tool loop. `AiSession` has two
 * — `sendAnthropic` speaks the SDK's types, `sendGeneric` speaks
 * [AiClient] — and they share no code, so a green `AiSessionTest` says nothing
 * about the second one. This serves the second one's providers.
 *
 * Both clients take an injectable endpoint and `OkHttpClient`, so these tests
 * go through the real [GeminiAiClient] and [OpenAiClient] rather than a fake
 * implementing [AiClient]. That is deliberate and it is the whole value of the
 * fixture: a fake would exercise the loop's bookkeeping while leaving the JSON
 * these classes actually put on the wire — which is where a provider rejects a
 * request — untested.
 */
class ScriptedProviderApi(responses: List<String>) {

    private val server = MockWebServer()
    private val queued = ArrayDeque(responses)
    private val recorded = mutableListOf<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                // Repeating the last response rather than running out, for the
                // reason ScriptedApi does: a loop that asks one time too many
                // should fail on the assertion that names the problem, not on a
                // transport error thrown from inside the client.
                val body = if (queued.size > 1) queued.removeFirst() else queued.first()
                // **Streamed when the client asks to stream**, as the real
                // endpoints do. This only ever answered with JSON, and the chat
                // always streams -- so the streaming parsers, on the phone's own
                // org.json, never ran in this suite. ScriptedApi does the same
                // for Anthropic, for the same reason.
                val path = request.path.orEmpty()
                return when {
                    ":streamGenerateContent" in path -> sse(geminiEvents(body))
                    "\"stream\":true" in request.body.peek().readUtf8() -> sse(openAiEvents(body) + "[DONE]")
                    else -> MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
        }
        server.start()
    }

    /**
     * A Gemini client pointed here.
     *
     * `customEndpoint` is the whole URL including the model and the
     * `:generateContent` verb, because that is the shape [GeminiAiClient]
     * builds for itself — passing only a host would leave the route it
     * constructs untested.
     */
    fun geminiClient(apiKey: String? = "test-key", oauthToken: String? = null) = GeminiAiClient(
        apiKey = apiKey,
        oauthToken = oauthToken,
        model = MODEL,
        customEndpoint = server.url("/v1beta/models/$MODEL:generateContent").toString(),
    )

    /**
     * An OpenAI client pointed here.
     *
     * The base URL is given without `/v1` on purpose: the client appends the
     * route itself, and that is the line worth pinning.
     */
    fun openAiClient(apiKey: String? = "test-key") = OpenAiClient(
        apiKey = apiKey,
        customBaseUrl = server.url("/").toString(),
        model = MODEL,
        provider = AiProviderType.OPENAI,
    )

    val requestCount: Int get() = recorded.size

    fun body(index: Int): String = recorded[index].body.readUtf8()

    fun path(index: Int): String? = recorded[index].path

    fun header(index: Int, name: String): String? = recorded[index].getHeader(name)

    fun stop() = server.shutdown()

    /** One call for a fixture to script, in the provider-neutral terms. */
    data class Call(val id: String, val name: String, val args: Map<String, String>)

    companion object {
        const val MODEL = "test-model"

        private fun sse(events: List<String>) = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("") { "data: $it\n\n" })

        /**
         * A one-shot OpenAI response as the stream a server sends for it.
         *
         * Prose is split in two so there is more than one delta. A tool call is
         * split the way servers do it -- the first fragment names the call and
         * later ones carry only more arguments, with `"id"` and `"name"` sent as
         * JSON `null` -- because that null is what the phone's org.json reads
         * as the text "null", and nothing else in the suite would send it.
         */
        fun openAiEvents(response: String): List<String> {
            val choice = org.json.JSONObject(response).getJSONArray("choices").getJSONObject(0)
            val message = choice.getJSONObject("message")
            val events = mutableListOf<String>()
            val content = if (message.isNull("content")) "" else message.getString("content")
            if (content.isNotEmpty()) {
                val half = content.length / 2
                listOf(content.substring(0, half), content.substring(half)).filter { it.isNotEmpty() }.forEach {
                    events += """{"choices":[{"delta":{"content":${quote(it)}}}]}"""
                }
            }
            val calls = message.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val function = call.getJSONObject("function")
                    val arguments = function.getString("arguments")
                    val half = arguments.length / 2
                    events += """{"choices":[{"delta":{"content":null,"tool_calls":[{"index":$i,"id":${quote(call.getString("id"))},"type":"function","function":{"name":${quote(function.getString("name"))},"arguments":${quote(arguments.substring(0, half))}}}]}}]}"""
                    events += """{"choices":[{"delta":{"tool_calls":[{"index":$i,"id":null,"function":{"name":null,"arguments":${quote(arguments.substring(half))}}}]}}]}"""
                }
            }
            events += """{"choices":[{"delta":{},"finish_reason":${quote(choice.getString("finish_reason"))}}]}"""
            return events
        }

        /** A one-shot Gemini response as its stream: one event per part, then the finish. */
        fun geminiEvents(response: String): List<String> {
            val candidate = org.json.JSONObject(response).getJSONArray("candidates").getJSONObject(0)
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            val events = (0 until parts.length()).map { i ->
                """{"candidates":[{"content":{"role":"model","parts":[${parts.getJSONObject(i)}]}}]}"""
            }
            return events + """{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":${quote(candidate.getString("finishReason"))}}]}"""
        }

        fun quote(value: String) = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""

        private fun argsObject(args: Map<String, String>) =
            args.entries.joinToString(",", "{", "}") { "${quote(it.key)}:${quote(it.value)}" }

        // -- Gemini ----------------------------------------------------------

        fun geminiText(text: String) = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":${quote(text)}}]},
             "finishReason":"STOP"}]}
        """.trimIndent().replace("\n", "")

        /**
         * A Gemini turn that asks for tools, with a prose part in front.
         *
         * The leading text is not decoration. Gemini routinely narrates before
         * a call, so a response with only a `functionCall` part would let a
         * loop that discards the assistant's prose pass.
         */
        fun geminiToolCall(vararg calls: Call) = """
            {"candidates":[{"content":{"role":"model","parts":[
             {"text":"Let me look."},
             ${calls.joinToString(",") {
            """{"functionCall":{"name":${quote(it.name)},"args":${argsObject(it.args)}}}"""
        }}
             ]},"finishReason":"STOP"}]}
        """.trimIndent().replace("\n", "")

        // -- OpenAI ----------------------------------------------------------

        fun openAiText(text: String) = """
            {"choices":[{"message":{"role":"assistant","content":${quote(text)}},
             "finish_reason":"stop"}]}
        """.trimIndent().replace("\n", "")

        /**
         * An OpenAI turn that asks for tools.
         *
         * `content` is JSON `null`, which is what OpenAI actually sends
         * alongside `tool_calls`. Android's `optString` renders that as the
         * *string* `"null"`, and [OpenAiClient] guards against it — a fixture
         * using `""` here would leave that guard unexercised.
         */
        fun openAiToolCall(vararg calls: Call) = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
             ${calls.joinToString(",") {
            """{"id":${quote(it.id)},"type":"function","function":{"name":${quote(it.name)},
                "arguments":${quote(argsObject(it.args))}}}"""
        }}
             ]},"finish_reason":"tool_calls"}]}
        """.trimIndent().replace("\n", "")
    }
}
