package com.osamu.aide.ai.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * JSON `null`, read by the phone's own org.json.
 *
 * **The JVM tests cannot see this.** The org.json artifact they run against
 * reads a JSON null with `optString` as `""`; Android's platform copy reads it
 * as the four characters `"null"`. Every provider parser here passed its unit
 * test while, on a phone, a tool-call fragment with `"name": null` produced a
 * tool called `read_filenull` and a `null` argument became a path. Only a test
 * in this process, on this org.json, settles it -- CLAUDE.md's rule about
 * testing platform behaviour, not logic.
 */
class StreamNullOnDeviceTest {

    @Test
    fun the_platform_really_does_read_null_as_text() {
        // The premise, pinned: if Android ever stops doing this, the guards are
        // merely redundant, and this is the test that says so.
        assertEquals("null", JSONObject("""{"a":null}""").optString("a"))
    }

    @Test
    fun a_streamed_tool_call_survives_null_fragments() {
        val accumulator = OpenAiStreamAccumulator()
        accumulator.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}""",
        )
        accumulator.accept(
            """{"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"\"Main.kt\"}"}}]}}]}""",
        )
        accumulator.accept("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":null}}]}}],"finish_reason":null}""")

        val call = accumulator.parts().single() as AiPart.FunctionCall
        assertEquals("read_file", call.name)
        assertEquals("call_1", call.id)
        assertEquals(mapOf("path" to "Main.kt"), call.args)
        assertFalse("\"null\" reached the prose", accumulator.content.contains("null"))
    }

    @Test
    fun a_null_argument_is_not_a_path() {
        assertEquals(mapOf("path" to "Main.kt"), decodeArguments("""{"path":"Main.kt","limit":null}"""))
    }

    @Test
    fun a_gemini_call_with_a_null_argument_keeps_only_the_real_ones() {
        val accumulator = GeminiStreamAccumulator()
        accumulator.accept(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"Main.kt","limit":null}}}]}}]}""",
        )

        val call = accumulator.parts().single() as AiPart.FunctionCall
        assertEquals(mapOf("path" to "Main.kt"), call.args)
    }
}
