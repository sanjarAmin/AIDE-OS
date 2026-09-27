package com.osamu.aide.spike.ai

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A local stand-in for the Messages API.
 *
 * Exists so the platform half of spike R5 can be answered without an API key
 * and without spending anything. The question that motivated the spike is
 * whether a large JVM SDK **dexes, loads and runs on ART** -- and Jackson does
 * not care whether the JSON it is reflecting over came from Anthropic or from
 * 127.0.0.1. If the SDK cannot deserialise a response here, it cannot
 * deserialise one there either.
 *
 * What this deliberately cannot answer: whether the real API accepts the exact
 * request shape, and whether prompt caching reports a hit. Those are semantics
 * rather than platform, they need a real key, and
 * [AnthropicOnDeviceTest] keeps them as skipped tests rather than pretending a
 * fake settled them.
 *
 * Records the last request body so a test can assert what the SDK actually put
 * on the wire, which is the other thing a fake is good for.
 *
 * Built on MockWebServer rather than `com.sun.net.httpserver`. The latter is
 * **not on Android at any API level** -- `:toolchain:manager`'s `ArchiveServer`
 * uses it and works only because that fixture is a JVM unit test. Copying it
 * into an instrumented test fails with `ClassNotFoundException` at run time.
 */
internal class FakeAnthropic(private val streamChunks: List<String> = emptyList()) {

    private val server = MockWebServer()

    private val recorded = mutableListOf<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                return if (streamChunks.isEmpty()) {
                    MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(NON_STREAMING_RESPONSE)
                } else {
                    MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sse())
                }
            }
        }
        server.start()
    }

    /** What to hand [com.anthropic.client.okhttp.AnthropicOkHttpClient.Builder.baseUrl]. */
    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    val requests: Int get() = recorded.size

    val lastRequestBody: String get() = recorded.lastOrNull()?.body?.readUtf8().orEmpty()

    fun stop() = server.shutdown()

    private fun sse(): String = buildString {
        append(event("message_start", MESSAGE_START))
        append(event("content_block_start", CONTENT_BLOCK_START))
        streamChunks.forEach { chunk ->
            append(
                event(
                    "content_block_delta",
                    """{"type":"content_block_delta","index":0,""" +
                        """"delta":{"type":"text_delta","text":${quote(chunk)}}}""",
                ),
            )
        }
        append(event("content_block_stop", """{"type":"content_block_stop","index":0}"""))
        append(event("message_delta", MESSAGE_DELTA))
        append(event("message_stop", """{"type":"message_stop"}"""))
    }

    private fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"

    private fun quote(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val USAGE = """{"input_tokens":10,"output_tokens":5,""" +
            """"cache_creation_input_tokens":0,"cache_read_input_tokens":0}"""

        val NON_STREAMING_RESPONSE = """
            {"id":"msg_fake","type":"message","role":"assistant","model":"claude-opus-5",
             "content":[{"type":"text","text":"pong"}],
             "stop_reason":"end_turn","stop_sequence":null,"usage":$USAGE}
        """.trimIndent().replace("\n", "")

        val MESSAGE_START = """
            {"type":"message_start","message":{"id":"msg_fake","type":"message",
             "role":"assistant","model":"claude-opus-5","content":[],
             "stop_reason":null,"stop_sequence":null,"usage":$USAGE}}
        """.trimIndent().replace("\n", "")

        const val CONTENT_BLOCK_START =
            """{"type":"content_block_start","index":0,""" +
                """"content_block":{"type":"text","text":""}}"""

        const val MESSAGE_DELTA =
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},""" +
                """"usage":{"output_tokens":5}}"""
    }
}
