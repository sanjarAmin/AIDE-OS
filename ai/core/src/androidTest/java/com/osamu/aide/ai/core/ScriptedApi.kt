package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import okhttp3.mockwebserver.Dispatcher
import org.json.JSONArray
import org.json.JSONObject
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

                // **A streaming request gets a stream.** The session streams
                // whenever the panel is listening, and the SDK's streaming call
                // reads an event stream -- handed a plain JSON body it yields
                // no events at all, so the turn came back empty: no answer, no
                // tool call, no approval, and nothing anywhere saying why. Six
                // tests failed that way at once, which is what a full sweep is
                // for.
                //
                // Converted from the same fixture rather than scripted
                // separately, so a test writes one message and both transports
                // serve it.
                val streaming = runCatching { request.body.peek().readUtf8() }
                    .getOrDefault("")
                    .contains("\"stream\":true")
                return if (streaming) {
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(asEvents(body))
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(body)
                }
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

        /**
         * One scripted message, as the events the Messages API would send.
         *
         * Only the events `MessageAccumulator` needs to rebuild the message:
         * a start carrying everything but the content, a start/delta/stop per
         * content block, and a delta carrying the stop reason. Text arrives as
         * one `text_delta` and a tool call's input as one `input_json_delta` --
         * real traffic splits both across many, and splitting them here would
         * test the SDK's reassembly rather than ours.
         */
        internal fun asEvents(messageJson: String): String {
            val message = JSONObject(messageJson)
            val content = message.optJSONArray("content") ?: JSONArray()
            val stopReason = message.optString("stop_reason")

            val shell = JSONObject(messageJson).put("content", JSONArray())
            val out = StringBuilder()
            fun event(type: String, payload: JSONObject) {
                payload.put("type", type)
                out.append("event: ").append(type).append('\n')
                    .append("data: ").append(payload).append("\n\n")
            }

            event("message_start", JSONObject().put("message", shell))
            for (index in 0 until content.length()) {
                val block = content.getJSONObject(index)
                when (block.optString("type")) {
                    "text" -> {
                        event(
                            "content_block_start",
                            JSONObject().put("index", index).put(
                                "content_block",
                                JSONObject().put("type", "text").put("text", ""),
                            ),
                        )
                        event(
                            "content_block_delta",
                            JSONObject().put("index", index).put(
                                "delta",
                                JSONObject().put("type", "text_delta")
                                    .put("text", block.optString("text")),
                            ),
                        )
                    }

                    "thinking" -> {
                        event(
                            "content_block_start",
                            JSONObject().put("index", index).put(
                                "content_block",
                                JSONObject().put("type", "thinking")
                                    .put("thinking", "").put("signature", ""),
                            ),
                        )
                        event(
                            "content_block_delta",
                            JSONObject().put("index", index).put(
                                "delta",
                                JSONObject().put("type", "thinking_delta")
                                    .put("thinking", block.optString("thinking")),
                            ),
                        )
                        // The signature arrives as its own delta, and the loop
                        // has to replay it: a fixture that dropped it would let
                        // a session that loses signatures pass.
                        event(
                            "content_block_delta",
                            JSONObject().put("index", index).put(
                                "delta",
                                JSONObject().put("type", "signature_delta")
                                    .put("signature", block.optString("signature")),
                            ),
                        )
                    }

                    "tool_use" -> {
                        event(
                            "content_block_start",
                            JSONObject().put("index", index).put(
                                "content_block",
                                JSONObject().put("type", "tool_use")
                                    .put("id", block.optString("id"))
                                    .put("name", block.optString("name"))
                                    .put("input", JSONObject()),
                            ),
                        )
                        event(
                            "content_block_delta",
                            JSONObject().put("index", index).put(
                                "delta",
                                JSONObject().put("type", "input_json_delta")
                                    .put("partial_json", block.optJSONObject("input").toString()),
                            ),
                        )
                    }
                }
                event("content_block_stop", JSONObject().put("index", index))
            }
            event(
                "message_delta",
                JSONObject()
                    .put("delta", JSONObject().put("stop_reason", stopReason).put("stop_sequence", JSONObject.NULL))
                    .put("usage", JSONObject(USAGE)),
            )
            event("message_stop", JSONObject())
            return out.toString()
        }

        private fun quote(value: String) = "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
    }
}
