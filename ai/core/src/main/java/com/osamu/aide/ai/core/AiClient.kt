package com.osamu.aide.ai.core

import com.anthropic.models.messages.OutputConfig

/**
 * Common representation of message roles across providers.
 */
enum class AiRole {
    USER,
    ASSISTANT,
}

/**
 * Individual content components within an AI message.
 */
sealed interface AiPart {
    data class Text(val text: String) : AiPart
    data class Thought(val thought: String, val signature: String? = null) : AiPart
    data class FunctionCall(
        val id: String,
        val name: String,
        val args: Map<String, String>,
    ) : AiPart
    data class FunctionResponse(
        val id: String,
        val name: String,
        val content: String,
        val isError: Boolean = false,
    ) : AiPart
}

/**
 * A conversational turn.
 */
data class AiMessage(
    val role: AiRole,
    val parts: List<AiPart>,
) {
    constructor(role: AiRole, text: String) : this(role, listOf(AiPart.Text(text)))
}

data class AiClientRequest(
    val systemInstruction: String,
    val messages: List<AiMessage>,
    val tools: List<AideTool> = emptyList(),
    val model: String = "",
    val effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
    val maxTokens: Long = 8_192L,
)

data class AiClientResponse(
    val parts: List<AiPart>,
    val finishReason: String? = null,
) {
    /**
     * The visible prose text from this response, omitting thoughts and function calls.
     */
    val text: String
        get() = parts.filterIsInstance<AiPart.Text>()
            .joinToString("\n") { it.text }
            .trim()

    val functionCalls: List<AiPart.FunctionCall>
        get() = parts.filterIsInstance<AiPart.FunctionCall>()
}

/**
 * Contract implemented by each AI model provider (Gemini, Anthropic, OpenAI, Custom).
 */
interface AiClient {
    val provider: AiProviderType
    val model: String

    suspend fun send(request: AiClientRequest): AiClientResponse
    suspend fun complete(context: CompletionContext): String?
}
