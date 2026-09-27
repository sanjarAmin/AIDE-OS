package com.osamu.aide.ai

import android.content.Context
import android.util.Log
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApiKeyStore
import com.osamu.aide.ai.core.Endpoint
import com.osamu.aide.toolchain.manager.ToolchainComponent
import com.osamu.aide.toolchain.manager.ToolchainManager
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * The `llama-server` behind [AiProviderType.LOCAL].
 *
 * **The provider's readiness is a running process, not a stored secret.** There
 * is no key to check: the peer is a program this app started on its own
 * loopback. So this writes the address into [ApiKeyStore.saveLocalBaseUrl] when
 * the server answers and clears it when it stops, and `Assistant` reads that as
 * "is there something to talk to".
 *
 * Everything awkward about starting it was settled by spike R16 and is repeated
 * here rather than shared with `LocalModelBenchmark`: that class measures a
 * model and this one keeps one alive for a chat session, and the two want
 * opposite lifetimes. What they must agree on is the *launch*, which is three
 * things and each was found by it failing without them --
 * `tools/localai/FINDINGS.md` §2:
 *
 *  - started through the linker, because the binary lives in app storage;
 *  - `LD_LIBRARY_PATH`, because its `RUNPATH` is Termux's prefix;
 *  - the working directory in `lib/`, because a multi-variant build selects its
 *    CPU backend by **scanning a directory**, and `GGML_BACKEND_PATH` names a
 *    single file. §8. A build shipping variants launched the old way loads the
 *    weakest one it can find, or none.
 */
class LocalModelServer(
    private val context: Context,
    private val toolchain: ToolchainManager,
    private val keys: ApiKeyStore,
) {

    private var process: Process? = null
    private val log = StringBuilder()

    /**
     * Forgets an address left behind by a process that is gone.
     *
     * **Found by driving the app.** Force-stopping it kills the server, but
     * nothing runs to clear the address it published, so the next launch read
     * `local.baseUrl`, reported the provider ready, and sent the first message
     * to a port nobody was listening on -- a connection refused where the
     * honest answer is "start the server".
     *
     * Probed rather than assumed cleared, because the opposite case is real
     * too: a `llama-server` is an ordinary child process and can outlive an app
     * that crashed rather than being force-stopped. If it still answers it is
     * still usable, and dropping the address would strand it holding a
     * gigabyte of model with nothing able to stop it.
     */
    fun adoptOrForgetExistingServer() {
        val address = keys.localBaseUrl()?.takeIf { it.isNotBlank() } ?: return
        if (!answersHealth(address)) {
            Log.i(TAG, "clearing stale address $address; nothing is listening")
            keys.saveLocalBaseUrl(null)
        }
    }

    /** True while a server this class started is still alive. */
    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * Why the server cannot start, or null when it could.
     *
     * Separate from [start] so a screen can say what is missing *before*
     * offering a button that fails. The order matters: the engine is 12 MB and
     * a model is 0.5 to 4.7 GB, so naming the engine first sends someone to the
     * cheap download.
     */
    fun unavailableReason(model: ToolchainComponent): String? = when {
        engineRoot() == null -> "The llama.cpp engine is not installed."
        toolchain.installedFile(model) == null -> "${model.displayName} is not downloaded."
        else -> null
    }

    /**
     * Starts the server for [model] and returns its base URL, or null.
     *
     * Blocking, and deliberately: a caller has to wait for `/health` before the
     * first request either way, and hiding that in a coroutine here would only
     * move the decision about which dispatcher to use away from the caller that
     * knows. Call it on IO.
     */
    fun start(model: ToolchainComponent): String? {
        if (isRunning) return keys.localBaseUrl()
        val root = engineRoot() ?: return null
        val modelFile = toolchain.installedFile(model) ?: return null

        // A port the OS says is free, rather than a fixed one: a second server
        // on 8080 -- the Node HTTP template's default -- would fail to bind,
        // and the failure would name a port the user chose for something else.
        val port = runCatching { ServerSocket(0).use { it.localPort } }.getOrNull() ?: return null
        val libraryDir = File(root, "lib")

        val started = runCatching {
            ProcessBuilder(
                LINKER,
                File(root, "bin/llama-server").absolutePath,
                "--model", modelFile.absolutePath,
                "--host", HOST,
                "--port", port.toString(),
                // Smaller than a desktop would use, on purpose. The assistant
                // sends the project listing with every question and a phone
                // pays for context twice -- in KV cache memory, on a model that
                // may already be most of RAM, and in prompt-reading time, which
                // is the number `tools/localai/FINDINGS.md` §8 says to watch.
                "--ctx-size", CONTEXT_TOKENS.toString(),
                "--jinja",
                "--no-webui",
            ).apply {
                // **In lib/, so the CPU backend variants are found.** Not
                // cosmetic: see this class's KDoc.
                directory(libraryDir)
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = libraryDir.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }.start()
        }.getOrElse {
            Log.w(TAG, "llama-server did not start", it)
            return null
        }
        val address = "http://$HOST:$port"
        adopt(started, address)

        if (!awaitHealth(address, started)) {
            stop()
            return null
        }
        // Only now: a URL published before the server answers would let the
        // first chat message race the model load and fail as a refused
        // connection, which reads as the feature being broken.
        keys.saveLocalBaseUrl(address)
        return address
    }

    /**
     * Takes ownership of a launched server: drains its output and watches it die.
     *
     * **Internal so a test can hand it an ordinary short-lived process.** The
     * death watch is worth asserting and starting a real `llama-server` to
     * assert it would need a gigabyte of model on the device, which makes the
     * test a skip on most of them -- and a skip reports as OK. Any process that
     * exits exercises the same path, because the signal *is* the output stream
     * closing.
     */
    @Synchronized
    internal fun adopt(started: Process, address: String) {
        process = started

        // Drained on a daemon thread. A process whose output nobody reads fills
        // its pipe buffer and stops, which would present as a server that
        // answered `/health` and then hung mid-generation.
        //
        // **The drain doubles as the death watch**, which is why it is handed
        // the address: `forEachLine` returns when the child closes its output,
        // and nothing else in this class ever learns that the child is gone.
        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) {
                        log.appendLine(line)
                        if (log.length > LOG_LIMIT) log.delete(0, log.length - LOG_LIMIT)
                    }
                }
            }
            noteExit(started, address)
        }.apply { isDaemon = true }.start()
    }

    /** Stops the server and withdraws the address. */
    @Synchronized
    fun stop() {
        // Cleared first. A crash between destroying the process and clearing
        // this would leave a dead address behind, and every later session would
        // believe a server was running.
        keys.saveLocalBaseUrl(null)
        process?.destroyForcibly()
        process = null
    }

    /**
     * Withdraws the address of a server that died without being asked to.
     *
     * **Measured, not anticipated.** After a termination run the app was still
     * up, `llama-server` was gone from `ps`, and `local.baseUrl` still named
     * port 46819 -- so `ApiKeyStore.isReady(LOCAL)` was true and the next
     * message would have gone to a closed port. [adoptOrForgetExistingServer]
     * does not cover this: it runs at launch, and this app had not restarted.
     * A 1.5B holding a gigabyte on a phone with 218 MB free is a plausible lmkd
     * target, so the child dying mid-session is the normal case and not the
     * exotic one. `tools/localai/FINDINGS.md` §10.
     *
     * Guarded on identity, because a [stop] and a fresh [start] can both happen
     * before the old drain thread wakes: clearing by address alone would
     * withdraw the *new* server's URL.
     */
    @Synchronized
    private fun noteExit(exited: Process, address: String) {
        if (process !== exited) return
        process = null
        if (keys.localBaseUrl() == address) {
            Log.w(TAG, "llama-server exited on its own; withdrawing $address")
            keys.saveLocalBaseUrl(null)
        }
    }

    /** The last of the server's output, for a failure a user has to act on. */
    fun recentLog(): String = synchronized(log) { log.toString().lines().takeLast(8).joinToString("\n") }

    private fun engineRoot(): File? {
        val component = ToolchainComponent.llamaCpp(android.os.Build.SUPPORTED_ABIS.first()) ?: return null
        // `bin/llama-server`'s install directory, two levels above the marker.
        return toolchain.installedFile(component)?.parentFile?.parentFile
    }

    /**
     * Waits for `/health`, giving up if the process dies first.
     *
     * Loading a 1.1 GB model off flash takes seconds and a 4.4 GB one takes
     * considerably longer, so the timeout is generous. Checking `isAlive` each
     * round is what turns "it exited immediately" into a prompt failure rather
     * than a two-minute wait.
     */
    private fun awaitHealth(address: String, started: Process): Boolean {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) return false
            if (answersHealth(address)) return true
            Thread.sleep(250)
        }
        return false
    }

    /** One `/health` probe. Short timeouts: this is loopback, or it is nothing. */
    private fun answersHealth(address: String): Boolean = runCatching {
        (URL("$address/health").openConnection() as HttpURLConnection).run {
            connectTimeout = 1_000
            readTimeout = 1_000
            responseCode == 200
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "LocalModelServer"

        /** Loopback only. The manifest's network-security-config permits this and no more. */
        const val HOST = "127.0.0.1"
        const val LINKER = "/system/bin/linker64"
        const val CONTEXT_TOKENS = 4096
        const val HEALTH_TIMEOUT_MS = 180_000L
        const val LOG_LIMIT = 8_000
    }
}
