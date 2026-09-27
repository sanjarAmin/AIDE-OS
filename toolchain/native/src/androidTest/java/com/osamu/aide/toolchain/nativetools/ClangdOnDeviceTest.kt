package com.osamu.aide.toolchain.nativetools

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Can `clangd` work here at all?
 *
 * M7's deliverable names it beside the compiler, and everything the compiler
 * needed a workaround for applies again: it is started through the dynamic
 * linker, so `/proc/self/exe` misleads it, and it may not execute any program
 * of its own. Two things about clangd make that worse rather than better --
 * it is a long-lived server rather than a one-shot process, and its usual way
 * of discovering system headers is **to run the compiler and ask it**
 * (`--query-driver`), which is exactly what this platform forbids.
 *
 * So this establishes the answer before an LSP client is written on top of it:
 * clangd starts, speaks LSP over stdio, and — the part that cannot be faked —
 * *parses* a file well enough to report a real diagnostic at the right line.
 * A server that started and answered `initialize` would satisfy a weaker test
 * while being useless, because answering `initialize` needs no headers.
 */
@RunWith(AndroidJUnit4::class)
class ClangdOnDeviceTest {

    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var work: File
    private var server: Process? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "toolchain/usr")
        assumeTrue(
            "no C/C++ toolchain staged; see ClangToolchainOnDeviceTest",
            File(root, "bin/clangd").exists(),
        )
        work = File(context.filesDir, "clangd-work").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        server?.destroyForcibly()
    }

    private val triple: String
        get() = if (Build.SUPPORTED_ABIS.first().startsWith("arm64")) {
            "aarch64-linux-android"
        } else {
            "x86_64-linux-android"
        }

    /**
     * The flags clang cannot derive for itself, handed to clangd the way clangd
     * takes them.
     *
     * `compile_flags.txt` beside the source, rather than `compile_commands.json`:
     * every file in this project is compiled with the same flags, and the
     * simpler format is one clangd finds by walking up from the file itself.
     *
     * The resource directory is the one that matters. Without it clangd looks
     * under `/apex/com.android.runtime/lib/clang/21`, finds no `stddef.h`, and
     * reports errors in every system header — which looks like a broken sysroot
     * rather than a broken launch.
     */
    private fun writeCompileFlags() {
        val resourceDir = File(root, "lib/clang").listFiles()
            ?.filter { it.isDirectory }
            ?.distinctBy { it.canonicalFile }
            ?.single()
        File(work, "compile_flags.txt").writeText(
            listOf(
                "-xc++",
                "-resource-dir=${resourceDir?.absolutePath}",
                "--sysroot=${root.absolutePath}",
                "-I${File(root, "include/$triple").absolutePath}",
                "-isystem${File(root, "include/c++/v1").absolutePath}",
            ).joinToString("\n") + "\n",
        )
    }

    private fun start(): Process {
        val launch = LinkerLaunch.forThisProcess()
        val plan = launch.plan(
            executable = File(root, "bin/clangd"),
            // --query-driver is deliberately NOT passed. It makes clangd
            // execute the compiler to learn its include paths, and nothing in
            // app storage may be executed -- the flags above replace it.
            arguments = listOf("--compile-commands-dir=${work.absolutePath}", "--log=error"),
            libraryPath = listOf(File(root, "lib")),
        )
        return ProcessBuilder(plan.command)
            .directory(work)
            .also { it.environment().putAll(plan.environment) }
            .start()
            .also { server = it }
    }

    /** One LSP frame: a `Content-Length` header, a blank line, then the body. */
    private fun OutputStream.send(message: JSONObject) {
        val body = message.toString().toByteArray()
        write("Content-Length: ${body.size}\r\n\r\n".toByteArray())
        write(body)
        flush()
    }

    /**
     * Reads one frame. Byte at a time for the header because the body is
     * counted in bytes and a buffered reader would consume past it.
     */
    private fun InputStream.receive(): JSONObject? {
        var length = -1
        while (true) {
            val line = StringBuilder()
            while (true) {
                val c = read()
                if (c == -1) return null
                if (c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            if (line.isEmpty()) break
            val text = line.toString()
            if (text.startsWith("Content-Length:", ignoreCase = true)) {
                length = text.substringAfter(':').trim().toInt()
            }
        }
        if (length < 0) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = read(body, read, length - read)
            if (n == -1) return null
            read += n
        }
        return JSONObject(String(body))
    }

    @Test
    fun clangd_starts_speaks_lsp_and_actually_parses() {
        writeCompileFlags()
        val source = File(work, "probe.cpp").apply {
            writeText(
                """
                #include <string>
                int broken(void) {
                    std::string s = "x"
                    return s.size();
                }
                """.trimIndent(),
            )
        }

        val process = start()
        val out = process.outputStream
        val input = process.inputStream

        out.send(
            JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put(
                    "params",
                    JSONObject()
                        .put("processId", JSONObject.NULL)
                        .put("rootUri", "file://${work.absolutePath}")
                        .put("capabilities", JSONObject()),
                ),
        )

        // The handshake. Notifications may arrive before the reply, so this
        // reads until the response to id 1 rather than assuming it is first.
        var initialised: JSONObject? = null
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val message = input.receive() ?: break
            if (message.optInt("id", -1) == 1 && message.has("result")) {
                initialised = message
                break
            }
        }
        assertTrue("clangd never answered initialize", initialised != null)
        Log.i(TAG, "clangd initialised")

        out.send(JSONObject().put("jsonrpc", "2.0").put("method", "initialized").put("params", JSONObject()))
        out.send(
            JSONObject()
                .put("jsonrpc", "2.0").put("method", "textDocument/didOpen")
                .put(
                    "params",
                    JSONObject().put(
                        "textDocument",
                        JSONObject()
                            .put("uri", "file://${source.absolutePath}")
                            .put("languageId", "cpp")
                            .put("version", 1)
                            .put("text", source.readText()),
                    ),
                ),
        )

        // The real assertion. A diagnostic here means clangd found the standard
        // library, parsed the file and located the fault -- none of which is
        // possible if the resource directory is wrong.
        var diagnostics: JSONObject? = null
        val until = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < until) {
            val message = input.receive() ?: break
            if (message.optString("method") == "textDocument/publishDiagnostics") {
                val list = message.getJSONObject("params").getJSONArray("diagnostics")
                if (list.length() > 0) {
                    diagnostics = list.getJSONObject(0)
                    break
                }
            }
        }

        assertTrue("clangd reported no diagnostics for a file that does not compile", diagnostics != null)
        val first = diagnostics!!
        Log.i(TAG, "clangd said: ${first.optString("message")} at line ${first.getJSONObject("range").getJSONObject("start").getInt("line")}")

        assertTrue(
            "the message does not look like a parse error: ${first.optString("message")}",
            first.optString("message").contains(";"),
        )
        // **Line 3, not 2, and the difference matters downstream.** The
        // semicolon is missing from line 2 (`std::string s = "x"`), but clangd
        // reports the fault where the parser discovered it -- at `return` on
        // line 3, the token that could not follow. Whatever draws squiggles in
        // the editor inherits that convention; a gutter marker placed on "the
        // line the user got wrong" would disagree with clangd by one line, on
        // the single commonest mistake in C++.
        assertEquals(
            3,
            first.getJSONObject("range").getJSONObject("start").getInt("line"),
        )
    }

    private companion object {
        const val TAG = "ClangdSpike"
    }
}
