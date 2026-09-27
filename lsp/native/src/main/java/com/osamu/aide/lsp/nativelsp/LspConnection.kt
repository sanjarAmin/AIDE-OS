package com.osamu.aide.lsp.nativelsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * One JSON-RPC conversation with a language server over a pair of streams.
 *
 * A single coroutine reads; everything else waits on a [CompletableDeferred]
 * keyed by request id. That shape is forced by the protocol rather than chosen:
 * responses may arrive in any order, and notifications the server sends on its
 * own -- diagnostics above all -- are interleaved with them. Reading in the
 * calling coroutine would mean whoever asked a question consuming somebody
 * else's answer.
 *
 * Writes are synchronised because a message is a header and a body written
 * separately; two coroutines writing at once would interleave them into
 * something the server cannot parse and would not complain about, because by
 * then the stream is garbage.
 */
internal class LspConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
) {

    private val nextId = AtomicInteger(1)
    private val pending = mutableMapOf<Int, CompletableDeferred<JSONObject>>()
    private val notifications = mutableMapOf<String, (JSONObject) -> Unit>()
    private val writeLock = Any()

    @Volatile private var reader: Job? = null
    @Volatile private var stopped = false

    fun start() {
        reader = scope.launch {
            try {
                while (true) {
                    val raw = LspFraming.read(input) ?: break
                    dispatch(JSONObject(raw))
                }
            } catch (failure: Exception) {
                // The server died, or the stream broke. Nothing here can fix
                // it; what matters is that no caller waits forever for a reply
                // that is never coming.
                failAll(failure)
            }
        }
    }

    private fun dispatch(message: JSONObject) {
        val id = if (message.has("id") && !message.isNull("id")) message.optInt("id", -1) else -1
        if (id >= 0 && (message.has("result") || message.has("error"))) {
            synchronized(pending) { pending.remove(id) }?.complete(message)
            return
        }
        val method = message.optString("method")
        if (method.isNotEmpty()) {
            synchronized(notifications) { notifications[method] }?.invoke(message)
        }
        // A server-to-client *request* (it has an id and a method) is ignored.
        // clangd sends few, and none this client needs to answer; leaving them
        // unanswered is what the protocol permits for a capability we never
        // advertised.
    }

    private fun failAll(cause: Exception) {
        val waiting = synchronized(pending) { pending.values.toList().also { pending.clear() } }
        waiting.forEach { it.completeExceptionally(cause) }
    }

    fun on(method: String, handler: (JSONObject) -> Unit) {
        synchronized(notifications) { notifications[method] = handler }
    }

    /**
     * Sends a request and waits, or returns null if it takes too long.
     *
     * Returns the raw `result`, which is **not always an object**:
     * `textDocument/definition` answers with an array. Narrowing to
     * `JSONObject` here would turn every definition reply into null, and the
     * feature would simply never work while nothing reported an error.
     */
    suspend fun request(method: String, params: JSONObject, timeoutMillis: Long): Any? {
        if (stopped) return null
        val id = nextId.getAndIncrement()
        val waiter = CompletableDeferred<JSONObject>()
        synchronized(pending) { pending[id] = waiter }

        val message = JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        return try {
            send(message)
            val reply = withTimeoutOrNull(timeoutMillis) { waiter.await() }
            if (reply == null) synchronized(pending) { pending.remove(id) }
            reply?.opt("result")
        } catch (failure: Exception) {
            synchronized(pending) { pending.remove(id) }
            null
        }
    }

    fun notify(method: String, params: JSONObject) {
        if (stopped) return
        runCatching {
            send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
        }
    }

    private fun send(message: JSONObject) {
        synchronized(writeLock) { LspFraming.write(output, message.toString()) }
    }

    fun close() {
        stopped = true
        reader?.cancel()
        failAll(IllegalStateException("the language server connection was closed"))
    }
}
