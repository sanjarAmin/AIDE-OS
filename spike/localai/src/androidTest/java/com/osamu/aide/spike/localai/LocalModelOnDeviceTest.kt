package com.osamu.aide.spike.localai

import android.content.Context
import android.security.NetworkSecurityPolicy
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * llama.cpp's server, started from app storage, serving a coding model.
 *
 * **Records rather than asserts**, like spike R15: each question's answer is
 * logged under `LocalAiSpike`, and a test fails only when the route itself did
 * not work. A small model that does not produce a tool call is an answer, not a
 * defect in the spike.
 *
 * **HTTP here is a raw socket, deliberately.** Whether the platform lets an app
 * speak cleartext to 127.0.0.1 is question 4, and going through
 * `HttpURLConnection` would let that policy block questions 1 to 3 before they
 * were asked. The policy is read separately, from `NetworkSecurityPolicy`,
 * which is what OkHttp in the app would consult.
 *
 * **`adb shell run-as` cannot answer any of this**: it may exec from app
 * storage where the app may not. `tools/clang/FINDINGS.md` section 7.
 */
@RunWith(AndroidJUnit4::class)
class LocalModelOnDeviceTest {

    private lateinit var context: Context
    private lateinit var prefix: File
    private lateinit var model: File
    private var server: Process? = null
    private val serverLog = StringBuffer()
    private var port = 0

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val staged = context.getExternalFilesDir(null)
        val archive = File(staged, ARCHIVE)
        // Chosen per run: `-Pandroid.testInstrumentationRunnerArguments.model=<file>`.
        // The question worth asking of each size is different -- speed of the
        // smallest, whether the larger ones use tools -- and one class asks both.
        val modelName = InstrumentationRegistry.getArguments().getString("model") ?: MODEL
        model = File(staged, modelName)
        assumeTrue("no llama.cpp staged: tools/localai/fetch-llama.sh, push $ARCHIVE to $staged", archive.isFile)
        assumeTrue("no model staged: tools/localai/fetch-model.sh, push $modelName to $staged", model.isFile)
        Log.w(TAG, "model: $modelName")

        prefix = File(context.filesDir, "llama")
        if (!File(prefix, "bin/llama-server").canRead()) {
            prefix.mkdirs()
            // Unpacked by a child of this process, so the files carry the
            // app's SELinux label rather than the shell's.
            ProcessBuilder("/system/bin/tar", "-xf", archive.absolutePath, "-C", prefix.absolutePath)
                .redirectErrorStream(true)
                .start()
                .apply { inputStream.readBytes(); waitFor(10, TimeUnit.MINUTES) }
        }
        assumeTrue("the archive unpacked without llama-server", File(prefix, "bin/llama-server").canRead())
    }

    @After
    fun tearDown() {
        server?.destroy()
        server?.waitFor(5, TimeUnit.SECONDS)
        server?.destroyForcibly()
    }

    private fun report(question: String, answer: String) {
        Log.w(TAG, "$question -> $answer")
    }

    /**
     * Starts `llama-server` through the dynamic linker and waits for `/health`.
     *
     * `LD_LIBRARY_PATH` is required for the reason `NodeOnDeviceTest` gives:
     * the binary's RUNPATH is Termux's prefix, which this app does not have.
     */
    private fun startServer(threads: Int = Runtime.getRuntime().availableProcessors()): Long {
        port = ServerSocket(0).use { it.localPort }
        val started = System.currentTimeMillis()
        val process = ProcessBuilder(
            LINKER,
            File(prefix, "bin/llama-server").absolutePath,
            "--model", model.absolutePath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "--ctx-size", "4096",
            "--threads", threads.toString(),
            // Chat templates from the model's own metadata, which is what makes
            // OpenAI-style tool calls work at all.
            "--jinja",
            "--no-webui",
        ).redirectErrorStream(true).apply {
            directory(context.filesDir)
            environment().apply {
                put("LD_LIBRARY_PATH", File(prefix, "lib").absolutePath)
                // **ggml finds its CPU backend by searching, and every place it
                // searches is wrong here.** It is a plugin, `libggml-cpu.so`,
                // looked for in Termux's compiled-in prefix (absent), beside
                // `/proc/self/exe` (which under this launch is
                // /system/bin/linker64), and in the working directory. None
                // holds it, so the model fails to load with "no backends are
                // loaded" -- Node's execPath problem again, one library down.
                // This names the file outright.
                put("GGML_BACKEND_PATH", File(prefix, "lib/libggml-cpu.so").absolutePath)
                put("HOME", context.filesDir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
            }
        }.start()
        server = process
        Thread {
            // Caught: tearDown destroys the server mid-read, the read throws,
            // and an uncaught exception on *any* thread kills the process --
            // which took every later test in the class with it the first time.
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    serverLog.append(line).append('\n')
                }
            }
        }.apply { isDaemon = true }.start()

        val deadline = started + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("llama-server exited with ${process.exitValue()}:\n${serverLog.takeLast(3000)}")
            }
            val health = runCatching { http("GET", "/health") }.getOrNull()
            if (health?.first == 200) {
                // What the server made of the model's chat template decides how
                // tool calls are parsed, so it is part of question 3's answer.
                serverLog.lines()
                    .filter { it.contains("chat format", ignoreCase = true) || it.contains("chat_template", ignoreCase = true) || it.contains("tool", ignoreCase = true) }
                    .take(6)
                    .forEach { Log.w(TAG, "server: ${it.substringAfter(" srv ").trim()}") }
                return System.currentTimeMillis() - started
            }
            Thread.sleep(250)
        }
        throw AssertionError("llama-server never became healthy:\n${serverLog.takeLast(3000)}")
    }

    /** A minimal HTTP/1.1 exchange over a socket. Returns status and body. */
    private fun http(method: String, path: String, body: String? = null): Pair<Int, String> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = REQUEST_TIMEOUT_MS
            val payload = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                append("Connection: close\r\n")
                if (body != null) {
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
            val raw = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
            val text = String(raw, Charsets.UTF_8)
            val split = text.indexOf("\r\n\r\n")
            val headers = text.substring(0, split)
            var content = text.substring(split + 4)
            val status = headers.substringAfter(' ').substringBefore(' ').toInt()
            if (headers.contains("Transfer-Encoding: chunked", ignoreCase = true)) content = dechunk(content)
            return status to content
        }
    }

    private fun dechunk(chunked: String): String {
        val out = StringBuilder()
        var rest = chunked
        while (true) {
            val line = rest.substringBefore("\r\n")
            val size = line.trim().toIntOrNull(16) ?: break
            if (size == 0) break
            rest = rest.substringAfter("\r\n")
            out.append(rest, 0, size)
            rest = rest.substring(size).removePrefix("\r\n")
        }
        return out.toString()
    }

    private fun chat(messages: JSONArray, maxTokens: Int, tools: JSONArray? = null): JSONObject {
        val request = JSONObject()
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.2)
            .apply { if (tools != null) put("tools", tools) }
        val (status, body) = http("POST", "/v1/chat/completions", request.toString())
        assertTrue("chat failed with $status: ${body.take(500)}", status == 200)
        return JSONObject(body)
    }

    private fun message(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    /** Question 1: does it start from app storage, and how long until it answers? */
    @Test
    fun the_server_starts_from_app_storage_and_answers_health() {
        val version = ProcessBuilder(LINKER, File(prefix, "bin/llama-server").absolutePath, "--version")
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath }
            .start()
            .let { p -> p.inputStream.bufferedReader().readText().trim().also { p.waitFor(30, TimeUnit.SECONDS) } }
        report("version", version.lines().lastOrNull { it.isNotBlank() } ?: version)

        val readyMs = startServer()
        report(
            "Q1 start",
            "healthy after $readyMs ms with ${model.length() / 1_048_576} MB model, " +
                "${Runtime.getRuntime().availableProcessors()} threads",
        )
    }

    /** Question 2a: generation speed on a short coding question. */
    @Test
    fun a_short_answer_and_how_fast_it_came() {
        startServer()
        val answer = chat(
            JSONArray()
                .put(message("system", "You are a concise Android coding assistant."))
                .put(message("user", "In Java, write a method that reverses a String.")),
            maxTokens = 160,
        )
        val content = answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val timings = answer.optJSONObject("timings")
        report(
            "Q2 generation",
            "prompt ${timings?.optInt("prompt_n")} tok at ${timings?.optDouble("prompt_per_second")?.format()} tok/s, " +
                "generated ${timings?.optInt("predicted_n")} tok at ${timings?.optDouble("predicted_per_second")?.format()} tok/s",
        )
        report("Q2 answer", content.take(400).replace('\n', ' '))
        assertTrue("an empty answer", content.isNotBlank())
    }

    /**
     * Question 2b: reading a prompt the size the assistant sends.
     *
     * The assistant sends the project's file list and the file being asked
     * about with every question. On a phone the time to read that, before the
     * first word appears, may be the number that decides whether this is
     * usable -- generation speed alone would hide it.
     */
    @Test
    fun reading_an_assistant_sized_prompt() {
        startServer()
        val listing = (1..60).joinToString("\n") { "src/main/java/com/example/app/feature$it/Screen$it.java" }
        val file = (1..40).joinToString("\n") { i ->
            "    private int compute$i(int value) { return value * $i + offset$i; } // step $i of the pipeline"
        }
        val prompt = "Project files:\n$listing\n\nMainActivity.java:\n$file\n\nWhat does compute7 return for 3?"
        val answer = chat(JSONArray().put(message("user", prompt)), maxTokens = 48)
        val timings = answer.optJSONObject("timings")
        report(
            "Q2 prefill",
            "read ${timings?.optInt("prompt_n")} tok in ${timings?.optDouble("prompt_ms")?.div(1000)?.format()} s " +
                "(${timings?.optDouble("prompt_per_second")?.format()} tok/s) before the first word",
        )
        assertTrue("the prompt was not read", (timings?.optInt("prompt_n") ?: 0) > 500)
    }

    /**
     * Question 3: a well-formed tool call.
     *
     * The assistant reads and edits files through tools, so a model that only
     * chats is a smaller feature than the one the chat panel promises.
     */
    @Test
    fun whether_the_model_calls_a_tool_when_it_should() {
        startServer()
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
        val outcomes = (1..TOOL_TRIALS).map {
            val answer = chat(
                JSONArray()
                    .put(message("system", "You help with the user's Android project. Use tools to read files."))
                    .put(message("user", "What is in src/main/java/com/example/MainActivity.java?")),
                maxTokens = 128,
                tools = tools,
            )
            val msg = answer.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val call = msg.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
            when {
                call == null -> recoverFromText(msg.optString("content"))
                call.optString("name") != "read_file" -> "wrong tool ${call.optString("name")}"
                runCatching { JSONObject(call.optString("arguments")).getString("path") }.isFailure ->
                    "bad arguments ${call.optString("arguments")}"
                else -> "ok path=${JSONObject(call.optString("arguments")).getString("path")}"
            }
        }
        report(
            "Q3 tool calls",
            "${outcomes.count { it.startsWith("ok") }}/$TOOL_TRIALS as structured calls, " +
                "${outcomes.count { it.startsWith("in text") }}/$TOOL_TRIALS recoverable from the reply's text: $outcomes",
        )
    }

    /**
     * A call the model wrote as JSON in its reply instead of in `tool_calls`.
     *
     * Small models often choose the right tool and the right arguments and
     * then print them -- a fenced `json` block holding `name` and `arguments`
     * -- where the chat template expected its own markup. The server's parser
     * does not see those. Whether they are complete and well-formed decides
     * whether a lenient client-side parser would make such a model usable,
     * which is a different answer from "it cannot use tools".
     */
    private fun recoverFromText(content: String): String {
        val candidate = Regex("""\{[\s\S]*\}""").find(content)?.value
            ?: return "no call: ${content.take(80).replace('\n', ' ')}"
        val parsed = runCatching { JSONObject(candidate) }.getOrNull()
            ?: return "unparseable text: ${candidate.take(80).replace('\n', ' ')}"
        val name = parsed.optString("name")
        val arguments = parsed.optJSONObject("arguments")
            ?: runCatching { JSONObject(parsed.optString("arguments")) }.getOrNull()
        val path = arguments?.optString("path").orEmpty()
        return when {
            name != "read_file" -> "wrong tool in text: $name"
            path.isBlank() -> "no path in text: ${candidate.take(80).replace('\n', ' ')}"
            else -> "in text path=$path"
        }
    }

    /**
     * Question 4: would the app be allowed to speak plain HTTP to the server?
     *
     * Read from the policy OkHttp consults, for this test APK. It says what
     * the platform default is for an app with no network security config of
     * its own, which is what AIDE-OS ships today.
     */
    @Test
    fun whether_cleartext_to_loopback_is_permitted() {
        val policy = NetworkSecurityPolicy.getInstance()
        report(
            "Q4 cleartext",
            "targetSdk=${context.applicationInfo.targetSdkVersion} " +
                "anywhere=${policy.isCleartextTrafficPermitted} " +
                "127.0.0.1=${policy.isCleartextTrafficPermitted("127.0.0.1")} " +
                "localhost=${policy.isCleartextTrafficPermitted("localhost")}",
        )
    }

    private fun Double.format() = String.format("%.1f", this)

    private companion object {
        const val TAG = "LocalAiSpike"
        const val LINKER = "/system/bin/linker64"
        const val ARCHIVE = "llama.tar"
        const val MODEL = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf"
        const val STARTUP_TIMEOUT_MS = 180_000L
        const val REQUEST_TIMEOUT_MS = 300_000
        const val TOOL_TRIALS = 5
    }
}
