package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Adapts the Anthropic Messages API client to the unified [AiClient] interface.
 *
 * Preserves Anthropic prefix caching (tools -> system -> context -> conversation)
 * and adaptive thinking blocks.
 */
class AnthropicAiClient(
    private val client: AnthropicClient,
    override val model: String = AiProviderType.ANTHROPIC.defaultModel,
) : AiClient {

    override val provider: AiProviderType = AiProviderType.ANTHROPIC

    override suspend fun send(request: AiClientRequest): AiClientResponse = withContext(Dispatchers.IO) {
        toResponse(client.messages().create(params(request)))
    }

    /**
     * The streamed form of [send].
     *
     * **The SDK's own [MessageAccumulator] assembles the message.** Writing
     * that by hand would mean re-deriving how `content_block_start`,
     * `input_json_delta` and `content_block_stop` compose into a tool call, and
     * getting it subtly wrong for thinking blocks -- which Anthropic requires
     * back verbatim on the next turn, signature included, or it rejects the
     * turn. Accumulating with the SDK means the streamed message is the same
     * type the one-shot path returns, so [toResponse] maps both and the session
     * loop cannot tell them apart.
     *
     * Only `text_delta` reaches the UI. `thinking_delta` is deliberately not
     * forwarded: the panel would render the model's reasoning as though it were
     * the answer and then replace it, which reads as the assistant changing its
     * mind mid-sentence.
     */
    override suspend fun send(
        request: AiClientRequest,
        onTextDelta: (String) -> Unit,
    ): AiClientResponse = withContext(Dispatchers.IO) {
        val accumulator = MessageAccumulator.create()
        client.messages().createStreaming(params(request)).use { stream ->
            stream.stream().forEach { event ->
                accumulator.accumulate(event)
                event.contentBlockDelta().ifPresent { block ->
                    block.delta().text().ifPresent { delta ->
                        delta.text().takeIf { it.isNotEmpty() }?.let(onTextDelta)
                    }
                }
            }
        }
        toResponse(accumulator.message())
    }

    /**
     * The request, built once for both paths.
     *
     * The order is not taste: tools -> system -> context -> conversation is
     * what Anthropic's prefix caching wants, and spike R5's findings say so.
     * Rearranging anything here silently costs cache hits.
     */
    private fun params(request: AiClientRequest): MessageCreateParams {
        val anthropicMessages = request.messages.map { msg ->
            when (msg.role) {
                AiRole.USER -> {
                    val respParts = msg.parts.filterIsInstance<AiPart.FunctionResponse>()
                    if (respParts.isNotEmpty()) {
                        val blocks = respParts.map { resp ->
                            val builder = ToolResultBlockParam.builder().toolUseId(resp.id)
                            if (resp.isError) {
                                builder.content(resp.content).isError(true).build()
                            } else {
                                builder.content(resp.content).build()
                            }
                        }.map(ContentBlockParam::ofToolResult)
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(blocks)
                            .build()
                    } else {
                        val text = msg.parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.text }
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(text)
                            .build()
                    }
                }
                AiRole.ASSISTANT -> {
                    val text = msg.parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.text }
                    MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content(text)
                        .build()
                }
            }
        }

        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens)
            .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
            .outputConfig(OutputConfig.builder().effort(request.effort).build())
            .tools(request.tools.map { ToolUnion.ofTool(it.definition()) })
            .messages(anthropicMessages)

        if (request.systemInstruction.isNotBlank()) {
            builder.systemOfTextBlockParams(listOf(
                TextBlockParam.builder()
                    .text(request.systemInstruction)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build(),
            ))
        }

        return builder.build()
    }

    /** One mapping for both paths; see [send]. */
    private fun toResponse(response: Message): AiClientResponse {
        val parts = mutableListOf<AiPart>()
        for (block in response.content()) {
            block.text().ifPresent {
                parts += AiPart.Text(it.text())
            }
            block.thinking().ifPresent {
                parts += AiPart.Thought(it.thinking(), it.signature())
            }
            block.toolUse().ifPresent { call ->
                parts += AiPart.FunctionCall(
                    id = call.id(),
                    name = call.name(),
                    args = call.inputAsStrings(),
                )
            }
        }
        return AiClientResponse(parts = parts, finishReason = response.stopReason().toString())
    }

    override suspend fun complete(context: CompletionContext): String? = withContext(Dispatchers.IO) {
        val request = MessageCreateParams.builder()
            .model(model)
            .maxTokens(256)
            .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .system(COMPLETION_INSTRUCTIONS)
            .addUserMessage(
                buildString {
                    append("File: ").append(context.path).append("\n\n")
                    append("<before_cursor>\n")
                    append(context.before.takeLast(4000))
                    append("\n</before_cursor>\n\n")
                    append("<after_cursor>\n")
                    append(context.after.take(1000))
                    append("\n</after_cursor>")
                },
            )
            .build()

        val response = client.messages().create(request)
        val raw = response.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("")

        cleanCompletion(raw).takeIf { it.isNotEmpty() }
    }

    private fun ToolUseBlock.inputAsStrings(): Map<String, String> {
        val raw = runCatching { _input().convert(Map::class.java) }.getOrNull() ?: return emptyMap()
        return raw.entries.mapNotNull { (key, value) ->
            if (key is String && value != null) key to value.toString() else null
        }.toMap()
    }

    companion object {
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
