package com.osamu.aide.ui.benchmark

import com.osamu.aide.ui.benchmark.LocalModelBenchmark.ToolReply
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the benchmark sorts a model's reply to "use a tool".
 *
 * The three outcomes are the ones spike R16 found separate the model sizes, so
 * a misclassification would report the wrong verdict about a model -- that the
 * 1.5B cannot use tools when it can, or that the 0.5B can when it only talks.
 * Each fixture is the shape a real reply took.
 */
class LocalModelBenchmarkTest {

    private fun reply(content: String = "", toolCalls: JSONArray? = null) = JSONObject()
        .put("role", "assistant")
        .put("content", content)
        .apply { if (toolCalls != null) put("tool_calls", toolCalls) }

    @Test
    fun a_structured_call_is_structured() {
        val calls = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "read_file").put("arguments", """{"path":"src/Main.kt"}"""),
            ),
        )
        assertEquals(ToolReply.STRUCTURED, LocalModelBenchmark.classifyToolReply(reply(toolCalls = calls)))
    }

    /** The 1.5B's shape: the right call, printed as a fenced JSON block. */
    @Test
    fun a_call_written_as_json_in_the_reply_is_recognised() {
        val content = "```json\n{\n  \"name\": \"read_file\",\n  \"arguments\": {\n    " +
            "\"path\": \"src/main/java/com/example/MainActivity.java\"\n  }\n}\n```"
        assertEquals(ToolReply.IN_TEXT, LocalModelBenchmark.classifyToolReply(reply(content)))
    }

    /** The 0.5B's shape: prose about a file it never opened. */
    @Test
    fun a_description_of_the_file_is_no_call() {
        val content = "The `MainActivity.java` file is located in the `com/example` package and is a Java class."
        assertEquals(ToolReply.NONE, LocalModelBenchmark.classifyToolReply(reply(content)))
    }

    /** JSON that names no path is not a usable call, however call-shaped it looks. */
    @Test
    fun json_without_a_path_is_no_call() {
        val content = """{"name": "read_file", "arguments": {}}"""
        assertEquals(ToolReply.NONE, LocalModelBenchmark.classifyToolReply(reply(content)))
    }

    /**
     * The prompt is large enough to measure reading speed.
     *
     * At about four characters a token it is roughly 2,000 tokens -- the size R16
     * measured, which is what makes its numbers comparable with the emulator's.
     */
    @Test
    fun the_assistant_sized_prompt_is_assistant_sized() {
        val length = LocalModelBenchmark.assistantSizedPrompt().length
        assertTrue("prompt is only $length characters", length in 6_000..12_000)
    }
}
