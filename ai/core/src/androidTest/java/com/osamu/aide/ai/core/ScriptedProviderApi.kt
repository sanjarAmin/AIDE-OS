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
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
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
