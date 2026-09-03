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
    override val model: String = "gpt-4o",
    override val provider: AiProviderType = AiProviderType.OPENAI,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : AiClient {

    private val endpointUrl: String
        get() {
            val base = customBaseUrl?.trimEnd('/')?.removeSuffix("/chat/completions") ?: DEFAULT_BASE_URL
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

        val httpRequest = Request.Builder()
            .url(endpointUrl)
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
                throw IllegalStateException("OpenAI request failed (${response.code}): $responseBody")
            }
            parseResponse(responseBody)
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

        val httpRequest = Request.Builder()
            .url(endpointUrl)
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

    private fun parseResponse(body: String): AiClientResponse {
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

        return AiClientResponse(parts = parts, finishReason = finishReason)
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
