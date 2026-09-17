package com.osamu.aide.ui.benchmark

import com.osamu.aide.ui.benchmark.LocalModelBenchmark.ToolReply
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- What the engine can actually execute ----

    /** A real aarch64 `system_info` line from Termux's build, abbreviated. */
    private val termuxArmLog = """
        build: 0 (unknown) with Clang 21.0.0 for aarch64-unknown-linux-android24
        system_info: n_threads = 8 (n_threads_batch = 8) / 8 | CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | MATMUL_INT8 = 0 | DOTPROD = 0 | SVE = 0 | LLAMAFILE = 1 |
        main: server is listening on 127.0.0.1:8080
    """.trimIndent()

    /**
     * **The line that would have saved an afternoon.**
     *
     * `tools/localai/FINDINGS.md` §8: the phone read a prompt barely faster
     * than it wrote one, and explaining it took disassembling the shipped
     * `libggml-cpu.so`. The server announces it on its first line — the
     * benchmark simply never showed it.
     */
    @Test
    fun a_missing_acceleration_is_named_as_missing() {
        val described = LocalModelBenchmark.describeCpuBackend(termuxArmLog)

        assertNotNull(described)
        assertTrue("the enabled paths are not listed: $described", "NEON" in described!!)
        assertTrue(
            "MATMUL_INT8 = 0 was not reported as missing, which is the whole point: $described",
            "missing:" in described && "MATMUL_INT8" in described.substringAfter("missing:"),
        )
        assertTrue("DOTPROD was not reported as missing: $described", "DOTPROD" in described.substringAfter("missing:"))
    }

    /** With the kernels present, nothing is reported missing. */
    @Test
    fun a_build_with_the_kernels_reports_nothing_missing() {
        val log = termuxArmLog.replace("MATMUL_INT8 = 0", "MATMUL_INT8 = 1")
            .replace("DOTPROD = 0", "DOTPROD = 1")
            .replace("SVE = 0", "SVE = 1")

        val described = LocalModelBenchmark.describeCpuBackend(log)

        assertTrue("MATMUL_INT8 is on and was not listed: $described", "MATMUL_INT8" in described!!)
        assertTrue("nothing is missing, yet something was named: $described", "missing:" !in described)
    }

    /**
     * The thread count matches `name = digit` too, and is not a CPU feature.
     *
     * Reporting `n_threads` as an acceleration would be nonsense on a
     * single-core machine, where it is `n_threads = 1`.
     */
    @Test
    fun the_thread_count_is_not_reported_as_a_cpu_feature() {
        val described = LocalModelBenchmark.describeCpuBackend(
            "system_info: n_threads = 1 (n_threads_batch = 1) / 1 | CPU : NEON = 1 |",
        )

        assertEquals("NEON", described)
    }

    /** A log without the line answers null rather than something invented. */
    @Test
    fun a_log_with_no_system_info_line_is_null() {
        assertNull(LocalModelBenchmark.describeCpuBackend("main: server is listening\nall good\n"))
    }
}
