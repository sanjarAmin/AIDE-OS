package com.osamu.aide.ai.core

import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Gemini API client for AIDE-OS.
 *
 * Implements native Function Calling, system instructions, thinking config,
 * and handles both Google OAuth 2.0 access tokens and Gemini API keys.
 */
class GeminiAiClient(
    private val apiKey: String? = null,
    private val oauthToken: String? = null,
    override val model: String = AiProviderType.GEMINI.defaultModel,
    private val customEndpoint: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : AiClient {

    override val provider: AiProviderType = AiProviderType.GEMINI

    override suspend fun send(request: AiClientRequest): AiClientResponse = withContext(Dispatchers.IO) {
        val url = customEndpoint ?: "$BASE_URL/models/$model:generateContent"

        val payload = JSONObject().apply {
            if (request.systemInstruction.isNotBlank()) {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", request.systemInstruction)))
                })
            }

            put("contents", encodeContents(request.messages))

            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().apply {
                    put("function_declarations", encodeFunctionDeclarations(request.tools))
                }))
            }

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", request.maxTokens)
                if (model.contains("3.7") || model.contains("flash")) {
                    val budget = if (request.effort == OutputConfig.Effort.LOW) 512 else 2048
                    put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
                }
            })
        }

        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                when {
                    !oauthToken.isNullOrBlank() -> header("Authorization", "Bearer $oauthToken")
                    !apiKey.isNullOrBlank() -> header("x-goog-api-key", apiKey)
                }
            }
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini request failed (${response.code}): $responseBody")
            }
            parseResponse(responseBody)
        }
    }

    override suspend fun complete(context: CompletionContext): String? = withContext(Dispatchers.IO) {
        val url = customEndpoint ?: "$BASE_URL/models/$model:generateContent"

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
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", COMPLETION_INSTRUCTIONS)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 256)
            })
        }

        val httpRequest = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                when {
                    !oauthToken.isNullOrBlank() -> header("Authorization", "Bearer $oauthToken")
                    !apiKey.isNullOrBlank() -> header("x-goog-api-key", apiKey)
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

    private fun encodeContents(messages: List<AiMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            val role = if (message.role == AiRole.USER) "user" else "model"
            val partsArray = JSONArray()

            for (part in message.parts) {
                when (part) {
                    is AiPart.Text -> {
                        partsArray.put(JSONObject().put("text", part.text))
                    }
                    is AiPart.Thought -> {
                        partsArray.put(JSONObject().put("text", part.thought))
                    }
                    is AiPart.FunctionCall -> {
                        val callObj = JSONObject().apply {
                            put("name", part.name)
                            put("args", JSONObject(part.args))
                        }
                        partsArray.put(JSONObject().put("functionCall", callObj))
                    }
                    is AiPart.FunctionResponse -> {
                        val respObj = JSONObject().apply {
                            put("name", part.name)
                            put("response", JSONObject().apply {
                                put("content", part.content)
                                put("is_error", part.isError)
                            })
                        }
                        partsArray.put(JSONObject().put("functionResponse", respObj))
                    }
                }
            }

            if (partsArray.length() > 0) {
                array.put(JSONObject().apply {
                    put("role", role)
                    put("parts", partsArray)
                })
            }
        }
        return array
    }

    private fun encodeFunctionDeclarations(tools: List<AideTool>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            val decl = JSONObject().apply {
                put("name", tool.name)
                put("description", tool.description)

                val propertiesObj = JSONObject()
                val requiredArray = JSONArray()

                tool.parameters.forEach { (name, param) ->
                    propertiesObj.put(name, JSONObject().apply {
                        put("type", param.type.uppercase())
                        put("description", param.description)
                    })
                }
                tool.required.forEach { requiredArray.put(it) }

                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", propertiesObj)
                    if (requiredArray.length() > 0) {
                        put("required", requiredArray)
                    }
                })
            }
            array.put(decl)
        }
        return array
    }

    private fun parseResponse(body: String): AiClientResponse {
        val json = JSONObject(body)
        val candidates = json.optJSONArray("candidates") ?: return AiClientResponse(emptyList())
        if (candidates.length() == 0) return AiClientResponse(emptyList())

        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optString("finishReason")
        val content = candidate.optJSONObject("content") ?: return AiClientResponse(emptyList(), finishReason)
        val partsArray = content.optJSONArray("parts") ?: return AiClientResponse(emptyList(), finishReason)

        val resultParts = mutableListOf<AiPart>()
        for (i in 0 until partsArray.length()) {
            val partObj = partsArray.getJSONObject(i)
            if (partObj.has("text")) {
                resultParts += AiPart.Text(partObj.getString("text"))
            } else if (partObj.has("functionCall")) {
                val callObj = partObj.getJSONObject("functionCall")
                val name = callObj.getString("name")
                val argsObj = callObj.optJSONObject("args")
                val argsMap = mutableMapOf<String, String>()
                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = argsObj.optString(key)
                    }
                }
                resultParts += AiPart.FunctionCall(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    args = argsMap,
                )
            }
        }

        return AiClientResponse(parts = resultParts, finishReason = finishReason)
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
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
