package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigParam
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext

/** Where the cursor is, and enough either side of it to continue from. */
data class CompletionContext(
    /** Path relative to the project root, for the language and the imports. */
    val path: String,
    val before: String,
    val after: String,
)

/**
 * One completion at the cursor.
 *
 * Supports both [AnthropicClient] and any [AiClient] (Gemini, OpenAI, etc.).
 */
class InlineCompleter(
    private val client: AnthropicClient?,
    private val dispatchers: DispatcherProvider,
    private val aiClient: AiClient? = null,
) {

    constructor(client: AnthropicClient, dispatchers: DispatcherProvider) : this(
        client = client,
        dispatchers = dispatchers,
        aiClient = null,
    )

    constructor(aiClient: AiClient, dispatchers: DispatcherProvider) : this(
        client = null,
        dispatchers = dispatchers,
        aiClient = aiClient,
    )

    /**
     * The text to insert at the cursor, or null when there is nothing useful.
     */
    suspend fun complete(context: CompletionContext): String? {
        if (aiClient != null) {
            return aiClient.complete(context)
        }

        if (client == null) return null

        val response = withContext(dispatchers.io) {
            client.messages().create(request(context))
        }

        val raw = response.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("")

        return cleanCompletion(raw).takeIf { it.isNotEmpty() }
    }

    private fun request(context: CompletionContext): MessageCreateParams =
        MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(INSTRUCTIONS)
            .addUserMessage(
                buildString {
                    append("File: ").append(context.path).append("\n\n")
                    append("<before_cursor>\n")
                    append(context.before.takeLast(WINDOW_BEFORE))
                    append("\n</before_cursor>\n\n")
                    append("<after_cursor>\n")
                    append(context.after.take(WINDOW_AFTER))
                    append("\n</after_cursor>")
                },
            )
            .build()

    private companion object {
        const val MODEL = "claude-opus-5"
        const val MAX_TOKENS = 256L
        const val WINDOW_BEFORE = 4_000
        const val WINDOW_AFTER = 1_000

        val INSTRUCTIONS = """
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

/**
 * The model's reply, reduced to something insertable.
 */
internal fun cleanCompletion(raw: String): String {
    var text = raw

    val fence = Regex("```[a-zA-Z+#]*\\n([\\s\\S]*?)```").find(text)
    if (fence != null) {
        text = fence.groupValues[1]
    } else {
        val opening = Regex("```[a-zA-Z+#]*\\n").find(text)
        if (opening != null) text = text.substring(opening.range.last + 1)
    }

    text = text.trimStart('\n')
    return if (text.isBlank()) "" else text.trimEnd(' ', '\t', '\n')
}
