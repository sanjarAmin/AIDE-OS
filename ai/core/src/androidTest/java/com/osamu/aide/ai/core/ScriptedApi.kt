package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A Messages API that answers from a script.
 *
 * The tool loop's whole job is what it sends on the *second* request — the one
 * carrying the tool results — so a fixture that returns the same thing every
 * time cannot test it. This serves a queued list of responses in order and
 * keeps every request body, which is where the assertions actually look.
 *
 * MockWebServer rather than `com.sun.net.httpserver`: the latter is not on
 * Android at any API level. `tools/ai/FINDINGS.md` section 1.
 */
internal class ScriptedApi(responses: List<String>) {

    private val server = MockWebServer()
    private val queued = ArrayDeque(responses)
    private val recorded = mutableListOf<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                // Repeating the last scripted response rather than 404-ing on a
                // short script: a loop that asks one more time than expected
                // should fail on the assertion that says so, not on a transport
                // error from inside the SDK.
                val body = if (queued.size > 1) queued.removeFirst() else queued.first()
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(body)
            }
        }
        server.start()
    }

    fun client(): AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey("test-key")
        .baseUrl(baseUrl)
        .build()

    /**
     * Where this server is, in the form the SDK's `baseUrl` wants.
     *
     * Trailing slash removed for the same reason `parseEndpoint` removes one:
     * the SDK appends `/v1/messages`, so leaving it produces `//v1/messages`.
     */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    val requestCount: Int get() = recorded.size

    fun body(index: Int): String = recorded[index].body.readUtf8()

    /** The path the SDK actually requested, which is what pins `/v1`. */
    fun path(index: Int): String? = recorded[index].path

    fun stop() = server.shutdown()

    companion object {
        private const val USAGE = """{"input_tokens":10,"output_tokens":5,""" +
            """"cache_creation_input_tokens":0,"cache_read_input_tokens":0}"""

        private fun message(content: String, stopReason: String) = """
            {"id":"msg_test","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[$content],"stop_reason":"$stopReason","stop_sequence":null,
             "usage":$USAGE}
        """.trimIndent().replace("\n", "")

        /** A finished turn: text, no tools. */
        fun text(text: String) = message(
            """{"type":"text","text":${quote(text)}}""",
            "end_turn",
        )

        /**
         * A turn that asks for tools, with a thinking block in front of them.
         *
         * The thinking block is not decoration. Adaptive thinking puts one in
         * front of a tool call in real traffic, and the loop has to send it
         * back unchanged -- so a fixture without one would let a session that
         * drops thinking blocks pass.
         */
        fun toolUse(vararg calls: Call) = message(
            (
                listOf(
                    """{"type":"thinking","thinking":"","signature":"sig_test"}""",
                ) + calls.map {
                    """{"type":"tool_use","id":${quote(it.id)},"name":${quote(it.name)},""" +
                        """"input":${it.inputJson}}"""
                }
                ).joinToString(","),
            "tool_use",
        )

        data class Call(val id: String, val name: String, val inputJson: String)

        private fun quote(value: String) = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
    }
}
