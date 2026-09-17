package com.osamu.aide.ai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI & OpenAI-compatible provider client.
 *
 * Works with OpenAI, Groq, OpenRouter, Mistral, DeepSeek, and local inference
 * runners (Ollama, vLLM) speaking the standard `/v1/chat/completions` protocol.
 */
class OpenAiClient(
    private val apiKey: String? = null,
    private val customBaseUrl: String? = null,
    override val model: String = AiProviderType.OPENAI.defaultModel,
    override val provider: AiProviderType = AiProviderType.OPENAI,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : AiClient {

    /**
     * Where requests go, or null when this client has nowhere legitimate to
     * send them.
     *
     * **Only OpenAI falls back to OpenAI.** A Custom client with no address
     * used to fall back too, which sent the Custom key -- often a key for some
     * other service entirely -- to api.openai.com. It refuses instead, here as
     * well as in `Assistant`, so no future caller that builds one directly can
     * bring the leak back.
     */
    private val endpointUrl: String?
        get() {
            val base = customBaseUrl?.takeIf { it.isNotBlank() }?.trimEnd('/')?.removeSuffix("/chat/completions")
                ?: DEFAULT_BASE_URL.takeIf { provider == AiProviderType.OPENAI }
                ?: return null
            return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        }

    override suspend fun send(request: AiClientRequest): AiClientResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", encodeMessages(request.systemInstruction, request.messages))
            if (request.tools.isNotEmpty()) {
                put("tools", encodeTools(request.tools))
            }
            put("max_tokens", request.maxTokens)
            put("temperature", 0.2)
        }

        val url = endpointUrl ?: throw IllegalStateException(
            "${provider.displayName} has no address to send requests to. Set one in Settings.",
        )
        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // The provider the user chose, not the protocol it speaks: a
                // Groq or Ollama failure reported as "OpenAI request failed"
                // sent the user to look at the wrong service.
                throw IllegalStateException(
                    "${provider.displayName} request failed (${response.code}): $responseBody",
                )
            }
            parseResponse(responseBody, offered = request.tools)
        }
    }

    override suspend fun complete(context: CompletionContext): String? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append("File: ").append(context.path).append("\n\n")
            append("<before_cursor>\n")
            append(context.before.takeLast(4000))
            append("\n</before_cursor>\n\n")
            append("<after_cursor>\n")
            append(context.after.take(1000))
            append("\n</after_cursor>")
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", COMPLETION_INSTRUCTIONS))
                put(JSONObject().put("role", "user").put("content", prompt))
            })
            put("max_tokens", 256)
            put("temperature", 0.1)
        }

        val url = endpointUrl ?: return@withContext null
        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                if (!apiKey.isNullOrBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext null
            val parsed = parseResponse(responseBody)
            cleanCompletion(parsed.text).takeIf { it.isNotEmpty() }
        }
    }

    private fun encodeMessages(systemInstruction: String, messages: List<AiMessage>): JSONArray {
        val array = JSONArray()
        if (systemInstruction.isNotBlank()) {
            array.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }

        for (message in messages) {
            val role = if (message.role == AiRole.USER) "user" else "assistant"
            val textParts = message.parts.filterIsInstance<AiPart.Text>()
            val callParts = message.parts.filterIsInstance<AiPart.FunctionCall>()
            val respParts = message.parts.filterIsInstance<AiPart.FunctionResponse>()

            if (respParts.isNotEmpty()) {
                for (resp in respParts) {
                    array.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", resp.id)
                        put("content", resp.content)
                    })
                }
            } else {
                val obj = JSONObject().put("role", role)
                val combinedText = textParts.joinToString("\n") { it.text }
                obj.put("content", combinedText)

                if (callParts.isNotEmpty()) {
                    val callsArray = JSONArray()
                    for (call in callParts) {
                        callsArray.put(JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", call.name)
                                put("arguments", JSONObject(call.args).toString())
                            })
                        })
                    }
                    obj.put("tool_calls", callsArray)
                }
                array.put(obj)
            }
        }
        return array
    }

    private fun encodeTools(tools: List<AideTool>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            val propertiesObj = JSONObject()
            val requiredArray = JSONArray()

            tool.parameters.forEach { (name, param) ->
                propertiesObj.put(name, JSONObject().apply {
                    put("type", param.type)
                    put("description", param.description)
                })
            }
            tool.required.forEach { requiredArray.put(it) }

            array.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", propertiesObj)
                        if (requiredArray.length() > 0) {
                            put("required", requiredArray)
                        }
                    })
                })
            })
        }
        return array
    }

    /**
     * @param offered the tools this request declared, or empty when it declared
     *   none. Only a name in this list can be recovered from the reply text --
     *   see [recoverWrittenCall] -- so the inline-completion path, which offers
     *   no tools, cannot have code it returns mistaken for a call.
     */
    private fun parseResponse(
        body: String,
        offered: List<AideTool> = emptyList(),
    ): AiClientResponse {
        val json = JSONObject(body)
        val choices = json.optJSONArray("choices") ?: return AiClientResponse(emptyList())
        if (choices.length() == 0) return AiClientResponse(emptyList())

        val choice = choices.getJSONObject(0)
        val finishReason = choice.optString("finish_reason")
        val message = choice.optJSONObject("message") ?: return AiClientResponse(emptyList(), finishReason)

        val parts = mutableListOf<AiPart>()
        val content = message.optString("content")
        if (content.isNotBlank() && content != "null") {
            parts += AiPart.Text(content)
        }

        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id")
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.getString("name")
                val argsRaw = fn.optString("arguments")
                val argsMap = mutableMapOf<String, String>()
                runCatching {
                    val argsObj = JSONObject(argsRaw)
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = argsObj.optString(key)
                    }
                }
                parts += AiPart.FunctionCall(id = id, name = name, args = argsMap)
            }
        }

        // **A call the model wrote out instead of making.** Small local models
        // choose the right tool and then print it as JSON in the reply, because
        // the server's parser never recognised the markup their chat template
        // emitted. Measured twice: Qwen2.5-Coder 1.5B did it 5/5, and the 7B
        // 3/3 with `--jinja` already on, so it is not something a bigger model
        // grows out of. `tools/localai/FINDINGS.md` §4.
        //
        // Without this the assistant looks like it is working and never reads a
        // file -- the shape `ai/core/FINDINGS.md` warns about, where the bug
        // returns a plausible answer.
        if (parts.none { it is AiPart.FunctionCall }) {
            recoverWrittenCall(content, offered)?.let { (call, remaining) ->
                return AiClientResponse(
                    // The prose without the JSON. Keeping the object as text
                    // too would show the user the mechanics of a call the app
                    // is about to make for them.
                    parts = listOfNotNull(remaining?.let(AiPart::Text), call),
                    finishReason = finishReason,
                )
            }
        }

        return AiClientResponse(parts = parts, finishReason = finishReason)
    }

    /**
     * A tool call the model wrote into its reply, or null.
     *
     * **The name is the guard, and it is what makes this safe.** An IDE
     * assistant is asked for JSON all the time -- show me a `package.json`,
     * what does this config mean -- so recovering "a reply containing an
     * object" would execute tools the user only asked to look at. The object
     * must name a tool *this request offered* and carry `arguments`, which
     * together is a shape nothing but a call has.
     *
     * Deliberately not anchored to the whole reply. The models observed wrap
     * the object in a ```json fence and sometimes a sentence, and rejecting
     * those would recover none of the cases that actually occur -- the name
     * check is doing the work, not the position.
     *
     * Returns the call and whatever prose surrounded it, so the caller can show
     * one and act on the other.
     */
    private fun recoverWrittenCall(
        content: String,
        offered: List<AideTool>,
    ): Pair<AiPart.FunctionCall, String?>? {
        if (offered.isEmpty() || content.isBlank()) return null
        val names = offered.mapTo(mutableSetOf()) { it.name }

        // Greedy from the first brace to the last: a call's arguments are
        // nested objects, and a lazy match stops at the first inner `}`.
        val match = Regex("""\{[\s\S]*\}""").find(content) ?: return null
        val parsed = runCatching { JSONObject(match.value) }.getOrNull() ?: return null

        val name = parsed.optString("name").takeIf { it in names } ?: return null
        // Either shape: an object, or the string OpenAI's own wire format uses.
        val arguments = parsed.optJSONObject("arguments")
            ?: runCatching { JSONObject(parsed.optString("arguments")) }.getOrNull()
            ?: return null

        val args = mutableMapOf<String, String>()
        val keys = arguments.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            args[key] = arguments.optString(key)
        }

        // No id: this call was never registered with the server, so there is
        // nothing for a `tool_call_id` to refer back to. The generic loop
        // matches results by name, which is the path Gemini already needs.
        val call = AiPart.FunctionCall(id = "", name = name, args = args)
        val prose = (content.take(match.range.first) + content.drop(match.range.last + 1))
            .replace("```json", "")
            .replace("```", "")
            .trim()
            .takeIf { it.isNotBlank() }
        return call to prose
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val COMPLETION_INSTRUCTIONS = """
            You complete code at a cursor inside an IDE on the user's phone.

            Reply with the code that belongs at the cursor and nothing else: no
            explanation, no markdown fence, no repetition of the text before the
            cursor. Match the surrounding indentation and style.

            Complete the current line or the current small block. Do not write
            the rest of the file. If nothing useful can be added at the cursor,
            reply with nothing at all.
        """.trimIndent()
    }
}
