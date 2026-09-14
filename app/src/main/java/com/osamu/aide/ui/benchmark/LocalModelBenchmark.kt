package com.osamu.aide.ui.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** What the benchmark found, in the order it asks. */
data class BenchmarkReport(
    val device: String,
    val model: String,
    val lines: List<Pair<String, String>>,
) {
    /** Plain text, for pasting into a chat. */
    fun asText(): String = buildString {
        appendLine("AIDE-OS local model benchmark")
        appendLine(device)
        appendLine("Model: $model")
        lines.forEach { (label, value) -> appendLine("$label: $value") }
    }.trim()
}

/**
 * Runs `llama-server` on this phone and measures what spike R16 could not:
 * how fast a coding model is on real hardware.
 *
 * **The questions are the spike's, asked on a phone.** Startup; writing speed;
 * how long a prompt the size the assistant sends takes to read before the first
 * word; whether the model calls a tool; and what the model costs in memory.
 * The emulator answered all of them but speed, because its CPU hides the
 * instructions ggml's fast paths need. `tools/localai/FINDINGS.md`.
 *
 * **HTTP is a raw socket, deliberately.** An app with no network security config
 * may not speak cleartext to 127.0.0.1 (R16 §5), and this screen is not the
 * place to change that policy for the whole app.
 */
class LocalModelBenchmark(
    private val context: Context,
    private val llamaRoot: File,
    private val model: File,
    private val modelName: String,
) {

    private var server: Process? = null
    private val serverLog = StringBuffer()
    private var port = 0

    /** Stops the server if one is running. Safe to call twice. */
    fun stop() {
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        server = null
    }

    fun run(onStep: (String) -> Unit): BenchmarkReport {
        val lines = mutableListOf<Pair<String, String>>()
        val device = describeDevice()
        try {
            onStep("Starting llama-server…")
            val startMs = startServer()
            lines += "Startup" to "ready in ${"%.1f".format(startMs / 1000.0)} s"
            threadsLine()?.let { lines += "Threads" to it }

            onStep("Writing a short answer…")
            val short = chat(
                JSONArray()
                    .put(message("system", "You are a concise Android coding assistant."))
                    .put(message("user", "In Java, write a method that reverses a String.")),
                maxTokens = 128,
            )
            val shortTimings = short.optJSONObject("timings")
            lines += "Writing" to "${shortTimings.rate("predicted_per_second")} tok/s " +
                "(${shortTimings?.optInt("predicted_n")} tokens)"

            onStep("Reading an assistant-sized prompt…")
            val prefill = chat(JSONArray().put(message("user", assistantSizedPrompt())), maxTokens = 16)
            val prefillTimings = prefill.optJSONObject("timings")
            lines += "Reading" to "${prefillTimings.rate("prompt_per_second")} tok/s — " +
                "${prefillTimings?.optInt("prompt_n")} tokens took " +
                "${"%.1f".format((prefillTimings?.optDouble("prompt_ms") ?: 0.0) / 1000)} s before the first word"
            // After the long prompt, when the context has been filled: near the
            // peak. Not "less free RAM", which the drive tried first -- the model
            // is memory-mapped, Android counts those pages as reclaimable, and
            // a 576 MB server reported as 129 MB less free.
            serverRssMb()?.let { lines += "Memory" to "server uses $it MB (resident)" }

            onStep("Asking it to use a tool (3 tries)…")
            val calls = (1..TOOL_TRIALS).map { toolTrial() }
            lines += "Tool calls" to "${calls.count { it == ToolReply.STRUCTURED }}/$TOOL_TRIALS structured, " +
                "${calls.count { it == ToolReply.IN_TEXT }}/$TOOL_TRIALS written as JSON in the reply, " +
                "${calls.count { it == ToolReply.NONE }}/$TOOL_TRIALS no call"
        } catch (failure: Throwable) {
            lines += "Failed" to (failure.message ?: failure::class.java.simpleName)
            lines += "Server log" to serverLog.toString().lines().filter { it.isNotBlank() }.takeLast(6).joinToString(" | ")
        } finally {
            stop()
        }
        return BenchmarkReport(device, modelName, lines)
    }

    private fun describeDevice(): String {
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        val features = Regex("""^Features\s*:\s*(.+)$""", RegexOption.MULTILINE).find(cpuinfo)?.groupValues?.get(1)
            ?.split(' ')?.filter { it in INTERESTING_FEATURES }?.distinct()?.joinToString(" ")
            ?: Regex("""^flags\s*:\s*(.+)$""", RegexOption.MULTILINE).find(cpuinfo)?.groupValues?.get(1)
                ?.split(' ')?.filter { it in INTERESTING_FEATURES }?.distinct()?.joinToString(" ")
            ?: "?"
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "$soc, ${Runtime.getRuntime().availableProcessors()} cores, " +
            "${"%.1f".format(memory.totalMem / GB)} GB RAM (${"%.1f".format(memory.availMem / GB)} GB free), " +
            "CPU features: $features"
    }

    /**
     * The server's resident memory, read from `/proc`.
     *
     * The pid comes from `Process.toString()`, which on Android is
     * `Process[pid=…]`: `java.lang.Process` has no `pid()` here. Null when that
     * shape ever changes, rather than a wrong number.
     */
    private fun serverRssMb(): Long? {
        val pid = Regex("""pid=(\d+)""").find(server.toString())?.groupValues?.get(1) ?: return null
        val status = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return null
        return Regex("""VmRSS:\s+(\d+) kB""").find(status)?.groupValues?.get(1)?.toLong()?.div(1024)
    }

    /** The thread count the server chose, from its `system_info` line: `n_threads = 4 (n_threads_batch = 4)`. */
    private fun threadsLine(): String? =
        Regex("""n_threads\s*=\s*(\d+)""").find(serverLog)?.groupValues?.get(1)

    /**
     * Starts `llama-server` through the dynamic linker and waits for `/health`.
     *
     * Two environment variables, each found by R16 failing without it:
     * `LD_LIBRARY_PATH`, because the binary's RUNPATH is Termux's prefix; and
     * `GGML_BACKEND_PATH`, because ggml looks for its CPU backend beside
     * `/proc/self/exe` -- which under this launch is the linker.
     */
    private fun startServer(): Long {
        port = ServerSocket(0).use { it.localPort }
        val started = System.currentTimeMillis()
        val process = ProcessBuilder(
            LINKER,
            File(llamaRoot, "bin/llama-server").absolutePath,
            "--model", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--ctx-size", "4096",
            "--jinja",
            "--no-webui",
        ).redirectErrorStream(true).apply {
            directory(context.filesDir)
            environment().apply {
                put("LD_LIBRARY_PATH", File(llamaRoot, "lib").absolutePath)
                put("GGML_BACKEND_PATH", File(llamaRoot, "lib/libggml-cpu.so").absolutePath)
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
            }
        }.start()
        server = process
        Thread {
            // Caught: stop() destroys the process mid-read, and an uncaught
            // exception on any thread kills the app.
            runCatching {
                process.inputStream.bufferedReader().forEachLine { serverLog.append(it).append('\n') }
            }
        }.apply { isDaemon = true }.start()

        val deadline = started + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw IllegalStateException("llama-server exited with ${process.exitValue()}")
            }
            if (runCatching { http("GET", "/health").first }.getOrNull() == 200) {
                return System.currentTimeMillis() - started
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("llama-server did not become ready in ${STARTUP_TIMEOUT_MS / 1000} s")
    }

    private fun toolTrial(): ToolReply {
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "read_file")
                    .put("description", "Read a file in the user's project and return its contents.")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                            .put("required", JSONArray().put("path")),
                    ),
            ),
        )
        val reply = chat(
            JSONArray()
                .put(message("system", "You help with the user's Android project. Use tools to read files."))
                .put(message("user", "What is in src/main/java/com/example/MainActivity.java?")),
            maxTokens = 128,
            tools = tools,
        ).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return classifyToolReply(reply)
    }

    private fun chat(messages: JSONArray, maxTokens: Int, tools: JSONArray? = null): JSONObject {
        val request = JSONObject()
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .apply { if (tools != null) put("tools", tools) }
        val (status, body) = http("POST", "/v1/chat/completions", request.toString())
        check(status == 200) { "llama-server answered $status: ${body.take(200)}" }
        return JSONObject(body)
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun JSONObject?.rate(key: String): String =
        this?.optDouble(key)?.takeIf { !it.isNaN() }?.let { "%.1f".format(it) } ?: "?"

    /** A minimal HTTP/1.1 exchange over a socket: status and body. */
    private fun http(method: String, path: String, body: String? = null): Pair<Int, String> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val payload = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
            val raw = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toString("UTF-8")
            val split = raw.indexOf("\r\n\r\n")
            val headers = raw.substring(0, split)
            val content = raw.substring(split + 4)
            val status = headers.substringAfter(' ').substringBefore(' ').toInt()
            return status to if (headers.contains("Transfer-Encoding: chunked", ignoreCase = true)) dechunk(content) else content
        }
    }

    private fun dechunk(chunked: String): String {
        val out = StringBuilder()
        var rest = chunked
        while (true) {
            val size = rest.substringBefore("\r\n").trim().toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            out.append(rest, 0, size)
            rest = rest.substring(size).removePrefix("\r\n")
        }
        return out.toString()
    }

    companion object {
        private const val LINKER = "/system/bin/linker64"
        private const val STARTUP_TIMEOUT_MS = 300_000L
        private const val REQUEST_TIMEOUT_MS = 600_000
        private const val TOOL_TRIALS = 3
        private const val GB = 1_073_741_824.0

        /**
         * The CPU features that decide llama.cpp's speed. ARM's dot-product and
         * 8-bit matrix multiply are what its quantised kernels use; SVE on some
         * recent cores; AVX2 and FMA are x86's, and their absence is why the
         * emulator was slow.
         */
        private val INTERESTING_FEATURES = setOf("asimd", "asimddp", "i8mm", "sve", "sve2", "bf16", "avx", "avx2", "fma", "f16c")

        /**
         * About the prompt the assistant sends: a project listing and a file.
         * ~2,000 tokens, the size spike R16 measured at 110 s on the emulator.
         */
        internal fun assistantSizedPrompt(): String {
            val listing = (1..60).joinToString("\n") { "src/main/java/com/example/app/feature$it/Screen$it.java" }
            val file = (1..40).joinToString("\n") { i ->
                "    private int compute$i(int value) { return value * $i + offset$i; } // step $i of the pipeline"
            }
            return "Project files:\n$listing\n\nMainActivity.java:\n$file\n\nWhat does compute7 return for 3?"
        }

        /**
         * Sorts a reply into a structured call, a call written as JSON in the
         * text, or none -- the distinction R16 found separates the 0.5B from the
         * 1.5B.
         */
        internal fun classifyToolReply(message: JSONObject): ToolReply {
            val call = message.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
            if (call?.optString("name") == "read_file") return ToolReply.STRUCTURED
            val candidate = Regex("""\{[\s\S]*\}""").find(message.optString("content"))?.value ?: return ToolReply.NONE
            val parsed = runCatching { JSONObject(candidate) }.getOrNull() ?: return ToolReply.NONE
            val arguments = parsed.optJSONObject("arguments")
                ?: runCatching { JSONObject(parsed.optString("arguments")) }.getOrNull()
            return if (parsed.optString("name") == "read_file" && !arguments?.optString("path").isNullOrBlank()) {
                ToolReply.IN_TEXT
            } else {
                ToolReply.NONE
            }
        }
    }

    enum class ToolReply { STRUCTURED, IN_TEXT, NONE }
}
