package com.osamu.aide.ai.core

import org.json.JSONObject
import java.io.BufferedReader

/**
 * Server-sent events, and the accumulators that turn a stream of them back
 * into one [AiClientResponse].
 *
 * **Why this is a separate file of pure functions.** Streaming is the part of
 * a provider client that a device test cannot corner: the failure modes are a
 * tool call whose arguments arrive in six fragments, a `finish_reason` on a
 * chunk that carries nothing else, and a keep-alive comment in the middle of a
 * sentence. Reassembly is decided here, off the wire and off a device, so
 * `StreamAccumulatorTest` can drive the awkward orderings directly. The clients
 * keep only the HTTP.
 *
 * One rule the shapes below share: **a stream must produce exactly what the
 * one-shot path would have produced.** The session loop, the tool dedup guard
 * and every existing test are written against [AiClientResponse], and a
 * streaming client that returned a subtly different response would break them
 * in ways that look like model misbehaviour rather than a parsing bug.
 */

/**
 * Reads an SSE body and hands each event's `data` payload to [action].
 *
 * Handles the three things real providers do that a naive `readLine` loop gets
 * wrong:
 *
 *  - **`[DONE]`**, OpenAI's sentinel, which is not JSON and must not reach a
 *    parser.
 *  - **Comment lines** (`: keep-alive`), which proxies inject to hold the
 *    connection open. Anything that is not a `data:` field is skipped rather
 *    than parsed.
 *  - **Multi-line `data:`**, where one event's payload is split across several
 *    `data:` lines and the spec says to join them with newlines. Gemini does
 *    not do this and OpenAI does not either, but a proxy between us and them
 *    may, and the cost of honouring it is four lines.
 */
internal fun forEachSseData(reader: BufferedReader, action: (String) -> Unit) {
    val event = StringBuilder()

    fun flush() {
        if (event.isEmpty()) return
        val payload = event.toString()
        event.clear()
        if (payload.trim() == DONE_SENTINEL) return
        action(payload)
    }

    while (true) {
        val line = reader.readLine() ?: break
        when {
            // An empty line ends an event. This is the only place a payload is
            // dispatched, which is what makes multi-line data work.
            line.isBlank() -> flush()
            line.startsWith("data:") -> {
                val chunk = line.removePrefix("data:").removePrefix(" ")
                if (chunk.trim() == DONE_SENTINEL) {
                    flush()
                    return
                }
                if (event.isNotEmpty()) event.append('\n')
                event.append(chunk)
            }
            // `:` comments, `event:`, `id:`, `retry:` -- none of which we use.
            else -> Unit
        }
    }
    flush()
}

private const val DONE_SENTINEL = "[DONE]"

/**
 * Reassembles an OpenAI-compatible `chat/completions` stream.
 *
 * **Tool calls are the hard part, and they are why this is a class.** Text
 * arrives as whole words and could be concatenated by anyone. A tool call
 * arrives as an `index`, then an `id` and a `name`, then its `arguments` as a
 * run of JSON *fragments* -- `{"pa`, `th":"a.`, `kt"}` -- which are not valid
 * JSON until the last one lands. So arguments are buffered as text per index
 * and parsed once, at the end.
 *
 * The `index` matters: a model asking for two tools interleaves their
 * fragments, and keying by arrival order instead would splice one call's
 * arguments into the other. Kept in a sorted map so the calls come out in the
 * order the model asked for them, which is the order they should run in.
 */
internal class OpenAiStreamAccumulator {

    private class Partial {
        var id: String = ""
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private val text = StringBuilder()
    private val calls = sortedMapOf<Int, Partial>()
    private var finishReason: String? = null

    /** The prose so far, for a client that wants to recover a written call. */
    val content: String get() = text.toString()

    /**
     * Folds one `data:` payload in and returns the text it added, or null.
     *
     * Returning the delta rather than invoking a callback keeps this pure: the
     * caller decides whether a delta reaches the UI, and a test can assert the
     * sequence of deltas without installing a sink.
     */
    fun accept(data: String): String? {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null

        // An error can arrive mid-stream, after a 200. Surfaced as an exception
        // rather than swallowed, because the alternative is a reply that simply
        // stops early and reads as the model having nothing to say.
        json.optJSONObject("error")?.let { error ->
            throw IllegalStateException(error.optString("message").ifBlank { error.toString() })
        }

        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.getJSONObject(0)

        choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
            ?.let { finishReason = it }

        val delta = choice.optJSONObject("delta") ?: return null

        val piece = delta.optString("content").takeIf { it.isNotBlank() && it != "null" }
        if (piece != null) text.append(piece)

        delta.optJSONArray("tool_calls")?.let { fragments ->
            for (i in 0 until fragments.length()) {
                val fragment = fragments.getJSONObject(i)
                // Absent on some servers that only ever send one call. Treating
                // a missing index as 0 keeps those working instead of dropping
                // every fragment on the floor.
                val index = fragment.optInt("index", 0)
                val partial = calls.getOrPut(index) { Partial() }
                fragment.optString("id").takeIf { it.isNotBlank() }?.let { partial.id = it }
                fragment.optJSONObject("function")?.let { function ->
                    function.optString("name").takeIf { it.isNotBlank() }
                        ?.let { partial.name.append(it) }
                    // `optString` returns "" for an absent key, so appending
                    // unconditionally is safe and keeps empty fragments -- which
                    // servers do send -- from needing a special case.
                    partial.arguments.append(function.optString("arguments"))
                }
            }
        }

        return piece
    }

    /** Everything accumulated, in the shape the one-shot path returns. */
    fun parts(): List<AiPart> {
        val parts = mutableListOf<AiPart>()
        if (text.isNotEmpty()) parts += AiPart.Text(text.toString())
        for (partial in calls.values) {
            val name = partial.name.toString()
            if (name.isBlank()) continue
            parts += AiPart.FunctionCall(
                id = partial.id,
                name = name,
                args = decodeArguments(partial.arguments.toString()),
            )
        }
        return parts
    }

    fun finishReason(): String? = finishReason
}

/**
 * Reassembles a Gemini `streamGenerateContent?alt=sse` stream.
 *
 * Simpler than OpenAI's in the way that matters: a `functionCall` arrives
 * whole, so there is nothing to splice. What it does instead is send **thought
 * parts interleaved with prose**, flagged by `"thought": true` on a part that
 * otherwise looks exactly like text. Appending those to the answer would put
 * the model's reasoning in the reply, so they are collected separately and
 * never returned as a text delta.
 */
internal class GeminiStreamAccumulator {

    private val text = StringBuilder()
    private val thoughts = StringBuilder()
    private val calls = mutableListOf<AiPart.FunctionCall>()
    private var finishReason: String? = null

    fun accept(data: String): String? {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null

        json.optJSONObject("error")?.let { error ->
            throw IllegalStateException(error.optString("message").ifBlank { error.toString() })
        }

        val candidates = json.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val candidate = candidates.getJSONObject(0)

        candidate.optString("finishReason").takeIf { it.isNotBlank() && it != "null" }
            ?.let { finishReason = it }

        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val added = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)

            part.optJSONObject("functionCall")?.let { call ->
                val args = mutableMapOf<String, String>()
                call.optJSONObject("args")?.let { argsObject ->
                    val keys = argsObject.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        args[key] = argsObject.optString(key)
                    }
                }
                calls += AiPart.FunctionCall(id = "", name = call.optString("name"), args = args)
            }

            val piece = part.optString("text").takeIf { it.isNotBlank() }
            if (piece != null) {
                if (part.optBoolean("thought")) thoughts.append(piece) else added.append(piece)
            }
        }

        if (added.isEmpty()) return null
        text.append(added)
        return added.toString()
    }

    fun parts(): List<AiPart> {
        val parts = mutableListOf<AiPart>()
        if (thoughts.isNotEmpty()) parts += AiPart.Thought(thoughts.toString())
        if (text.isNotEmpty()) parts += AiPart.Text(text.toString())
        parts += calls
        return parts
    }

    fun finishReason(): String? = finishReason
}

/**
 * Turns an OpenAI `arguments` string into the flat map the toolset expects.
 *
 * Shared by the streaming and one-shot paths so a call assembled from
 * fragments cannot decode differently from the same call sent whole -- the
 * divergence would show up as a tool that works unstreamed and misbehaves
 * streamed, which is a miserable thing to chase.
 *
 * Values are read with `optString`, which stringifies numbers and booleans.
 * That is deliberate: every tool here takes strings, and a model that sends
 * `{"line": 42}` should not have its call dropped for being well-typed.
 */
internal fun decodeArguments(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val args = mutableMapOf<String, String>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        args[key] = json.optString(key)
    }
    return args
}
