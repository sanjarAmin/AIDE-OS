package com.osamu.aide.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader

/**
 * Reassembly, driven directly.
 *
 * These are the cases a device test cannot arrange: a tool call whose
 * arguments arrive in fragments, two calls interleaved, a `finish_reason` on a
 * chunk with no delta, a keep-alive comment mid-sentence. Streaming has to
 * produce exactly what the one-shot path produces, and the only way to be sure
 * is to feed it the ugly orderings on purpose.
 */
class StreamAccumulatorTest {

    private fun reader(body: String) = BufferedReader(body.reader())

    private fun dataOf(body: String): List<String> =
        mutableListOf<String>().also { out -> forEachSseData(reader(body)) { out += it } }

    // -- the SSE envelope ----------------------------------------------------

    @Test
    fun `the done sentinel never reaches the parser`() {
        val events = dataOf("data: {\"a\":1}\n\ndata: [DONE]\n\n")

        assertEquals(listOf("{\"a\":1}"), events)
    }

    @Test
    fun `comments and unknown fields are skipped`() {
        // A proxy's keep-alive, landing between two chunks of one sentence.
        val events = dataOf(
            ": keep-alive\n\ndata: {\"n\":1}\n\nevent: ping\nid: 7\n\ndata: {\"n\":2}\n\n",
        )

        assertEquals(listOf("{\"n\":1}", "{\"n\":2}"), events)
    }

    @Test
    fun `a multi-line data field is one event joined by newlines`() {
        val events = dataOf("data: {\"a\":\ndata: 1}\n\n")

        assertEquals(listOf("{\"a\":\n1}"), events)
    }

    @Test
    fun `a body that ends without a blank line still yields its last event`() {
        // Measured against a server that closes the socket after the final
        // chunk: waiting for the boundary would drop the whole last token.
        val events = dataOf("data: {\"last\":true}")

        assertEquals(listOf("{\"last\":true}"), events)
    }

    // -- OpenAI --------------------------------------------------------------

    @Test
    fun `text deltas accumulate and are returned one at a time`() {
        val accumulator = OpenAiStreamAccumulator()

        val deltas = listOf("Hel", "lo ", "there").map { piece ->
            accumulator.accept("""{"choices":[{"delta":{"content":"$piece"}}]}""")
        }

        assertEquals(listOf("Hel", "lo ", "there"), deltas)
        assertEquals(listOf(AiPart.Text("Hello there")), accumulator.parts())
    }

    @Test
    fun `a tool call arriving in argument fragments parses once whole`() {
        val accumulator = OpenAiStreamAccumulator()

        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
                """"function":{"name":"read_file","arguments":""}}]}}]}""",
        )
        for (fragment in listOf("""{\"pa""", """th\":\"src/M""", """ain.kt\"}""")) {
            accumulator.accept(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$fragment"}}]}}]}""",
            )
        }

        val call = accumulator.parts().filterIsInstance<AiPart.FunctionCall>().single()
        assertEquals("read_file", call.name)
        assertEquals("call_1", call.id)
        assertEquals(mapOf("path" to "src/Main.kt"), call.args)
    }

    @Test
    fun `two interleaved calls keep their own arguments`() {
        // The failure this guards: keying by arrival order instead of `index`
        // splices one call's arguments into the other, and the model gets
        // blamed for asking to read a file named after a search query.
        val accumulator = OpenAiStreamAccumulator()

        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[""" +
                """{"index":0,"id":"a","function":{"name":"read_file","arguments":"{\"path\":\""}},""" +
                """{"index":1,"id":"b","function":{"name":"grep","arguments":"{\"query\":\""}}""" +
                """]}}]}""",
        )
        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"greeting\"}"}}]}}]}""",
        )
        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"A.kt\"}"}}]}}]}""",
        )

        val calls = accumulator.parts().filterIsInstance<AiPart.FunctionCall>()
        assertEquals(listOf("read_file", "grep"), calls.map { it.name })
        assertEquals(mapOf("path" to "A.kt"), calls[0].args)
        assertEquals(mapOf("query" to "greeting"), calls[1].args)
    }

    @Test
    fun `a fragment with no index belongs to the first call`() {
        // Some OpenAI-compatible servers omit `index` when there is only ever
        // one call. Dropping those fragments loses the arguments entirely.
        val accumulator = OpenAiStreamAccumulator()

        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"id":"x","function":{"name":"list_files","arguments":"{}"}}]}}]}""",
        )

        val call = accumulator.parts().filterIsInstance<AiPart.FunctionCall>().single()
        assertEquals("list_files", call.name)
        assertEquals(emptyMap<String, String>(), call.args)
    }

    @Test
    fun `a finish reason on an otherwise empty chunk is kept`() {
        val accumulator = OpenAiStreamAccumulator()

        assertNull(accumulator.accept("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""))

        assertEquals("tool_calls", accumulator.finishReason())
    }

    @Test
    fun `a null content delta adds nothing`() {
        // Sent by servers on the chunk that opens a tool call. Appending the
        // four characters "null" to the reply is a real symptom of getting it
        // wrong, and it looks like the model typing nonsense.
        val accumulator = OpenAiStreamAccumulator()

        assertNull(accumulator.accept("""{"choices":[{"delta":{"content":null}}]}"""))

        assertEquals(emptyList<AiPart>(), accumulator.parts())
    }

    @Test
    fun `an error mid-stream is raised rather than truncating the reply`() {
        val accumulator = OpenAiStreamAccumulator()
        accumulator.accept("""{"choices":[{"delta":{"content":"partial"}}]}""")

        val failure = assertThrows(IllegalStateException::class.java) {
            accumulator.accept("""{"error":{"message":"context length exceeded"}}""")
        }

        assertTrue(failure.message!!.contains("context length exceeded"))
    }

    @Test
    fun `malformed json is ignored rather than ending the stream`() {
        val accumulator = OpenAiStreamAccumulator()

        assertNull(accumulator.accept("not json at all"))
        accumulator.accept("""{"choices":[{"delta":{"content":"fine"}}]}""")

        assertEquals(listOf(AiPart.Text("fine")), accumulator.parts())
    }

    @Test
    fun `the content is exposed for written-call recovery`() {
        // A local model that writes its call into the reply is recovered by the
        // client, and the client needs the prose to do it. §4 of the local-AI
        // findings: streaming must not regress that.
        val accumulator = OpenAiStreamAccumulator()
        accumulator.accept("""{"choices":[{"delta":{"content":"{\"name\":"}}]}""")
        accumulator.accept("""{"choices":[{"delta":{"content":"\"read_file\"}"}}]}""")

        assertEquals("""{"name":"read_file"}""", accumulator.content)
    }

    // -- Gemini --------------------------------------------------------------

    @Test
    fun `gemini text parts accumulate`() {
        val accumulator = GeminiStreamAccumulator()

        val first = accumulator.accept("""{"candidates":[{"content":{"parts":[{"text":"one "}]}}]}""")
        val second = accumulator.accept("""{"candidates":[{"content":{"parts":[{"text":"two"}]}}]}""")

        assertEquals("one ", first)
        assertEquals("two", second)
        assertEquals(listOf(AiPart.Text("one two")), accumulator.parts())
    }

    @Test
    fun `a thought part is never a text delta`() {
        // Streaming thoughts into the bubble shows the user the model's
        // reasoning as if it were the answer.
        val accumulator = GeminiStreamAccumulator()

        val delta = accumulator.accept(
            """{"candidates":[{"content":{"parts":[{"text":"weighing it","thought":true}]}}]}""",
        )

        assertNull(delta)
        assertEquals(
            listOf(AiPart.Thought("weighing it")),
            accumulator.parts(),
        )
    }

    @Test
    fun `a gemini function call arrives whole`() {
        val accumulator = GeminiStreamAccumulator()

        accumulator.accept(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"grep","args":{"query":"x"}}}]}}]}""",
        )

        val call = accumulator.parts().filterIsInstance<AiPart.FunctionCall>().single()
        assertEquals("grep", call.name)
        assertEquals(mapOf("query" to "x"), call.args)
    }

    @Test
    fun `a chunk mixing prose and a call yields only the prose as a delta`() {
        val accumulator = GeminiStreamAccumulator()

        val delta = accumulator.accept(
            """{"candidates":[{"content":{"parts":[""" +
                """{"text":"Let me look. "},{"functionCall":{"name":"list_files","args":{}}}""" +
                """]}}]}""",
        )

        assertEquals("Let me look. ", delta)
        assertEquals(1, accumulator.parts().filterIsInstance<AiPart.FunctionCall>().size)
    }

    // -- shared argument decoding -------------------------------------------

    @Test
    fun `arguments decode numbers and booleans as strings`() {
        // Every tool here takes strings; a well-typed call must not be dropped.
        assertEquals(
            mapOf("line" to "42", "all" to "true", "path" to "a.kt"),
            decodeArguments("""{"line":42,"all":true,"path":"a.kt"}"""),
        )
    }

    @Test
    fun `unparseable arguments decode to an empty map rather than throwing`() {
        assertEquals(emptyMap<String, String>(), decodeArguments("{\"path\": "))
        assertEquals(emptyMap<String, String>(), decodeArguments(""))
    }
}
