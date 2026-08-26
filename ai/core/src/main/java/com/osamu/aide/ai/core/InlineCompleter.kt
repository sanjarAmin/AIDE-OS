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
 * Separate from [AiSession] because almost every decision differs.
 *
 * **No tools.** A completion that decided to go and read three files would
 * arrive after the user had finished typing the line themselves. It also could
 * not share the chat's cached prefix -- tools render first, so a request with a
 * different tool list is a different prefix -- and paying two cache entries to
 * make completion slower is the wrong trade twice over.
 *
 * **Low effort, small ceiling.** `docs/PLAN.md` names effort as the per-feature
 * cost lever and puts completion at the cheap end. The token ceiling is a
 * second guard: without one the model will happily write the rest of the class.
 *
 * **Bounded context.** The window either side of the cursor is capped rather
 * than sending the file, because a completion is requested often and the whole
 * file is mostly irrelevant to the next few tokens. See [WINDOW_BEFORE].
 */
class InlineCompleter(
    private val client: AnthropicClient,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * The text to insert at the cursor, or null when there is nothing useful.
     *
     * Null rather than an empty string so the caller cannot accidentally insert
     * nothing and call it a success -- "no suggestion" is a real answer here,
     * and the UI has to say so rather than flash.
     */
    suspend fun complete(context: CompletionContext): String? {
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

        /**
         * Enough for a line or a short block, not a method body.
         *
         * A completion the user has to read carefully is one they would have
         * been faster writing.
         */
        const val MAX_TOKENS = 256L

        /**
         * More before the cursor than after, because that is where the answer
         * usually is -- the imports, the surrounding method, the variable that
         * was just declared. What follows mostly says where to stop.
         */
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
 *
 * A pure function, and the part of completion actually worth testing: the model
 * is asked for bare code and mostly obliges, but "mostly" inserted straight
 * into a buffer means a stray ```kotlin fence in the middle of a file the user
 * then has to find and delete. Every rule here is a thing a model does.
 */
internal fun cleanCompletion(raw: String): String {
    var text = raw

    // A fenced block, with or without a language tag. Taken as *the* answer
    // rather than stripped in place: when the model does fence, anything
    // outside the fence is the explanation it was asked not to give.
    val fence = Regex("```[a-zA-Z+#]*\\n([\\s\\S]*?)```").find(text)
    if (fence != null) {
        text = fence.groupValues[1]
    } else {
        // An unterminated fence -- the token ceiling cut the reply mid-block.
        // Everything after the opening fence is still code.
        val opening = Regex("```[a-zA-Z+#]*\\n").find(text)
        if (opening != null) text = text.substring(opening.range.last + 1)
    }

    // Leading newlines go, leading spaces stay: the model is completing at a
    // cursor that is already indented, and eating its indentation would put
    // the continuation in column zero.
    text = text.trimStart('\n')

    // Trailing whitespace goes. A model that ends on a newline is padding, and
    // the editor supplies the next line's indent itself.
    return if (text.isBlank()) "" else text.trimEnd(' ', '\t', '\n')
}
