package com.osamu.aide.ai.core

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigParam
import com.anthropic.models.messages.ToolUnion

/** A plain text turn, for the cases that have no blocks to carry. */
fun userTurn(text: String): MessageParam = MessageParam.builder()
    .role(MessageParam.Role.USER)
    .content(text)
    .build()

fun assistantTurn(text: String): MessageParam = MessageParam.builder()
    .role(MessageParam.Role.ASSISTANT)
    .content(text)
    .build()

/**
 * Builds requests in the order prompt caching requires.
 *
 * **The layout here is a cost decision, not a style one.** Caching is
 * prefix-match and the request renders `tools` -> `system` -> `messages`, so
 * anything that changes between turns invalidates everything after it. The
 * ordering this class enforces:
 *
 * 1. **Tools** — fixed set, fixed order. First because they render first.
 * 2. **Stable instructions** — the assistant's standing brief, identical every
 *    turn.
 * 3. **Project context** — the file tree and open buffers, with the cache
 *    breakpoint on it. Changes when the project does, which is rarely relative
 *    to how often the user speaks.
 * 4. **Conversation** — everything volatile, after the last breakpoint.
 *
 * A timestamp, a cursor position or a "you are helping with X.kt" line anywhere
 * above step 4 silently costs the entire cache and returns a perfectly good
 * answer, so the mistake is invisible until the bill. `usage.cacheReadInputTokens`
 * is the only way to see it, and `PromptAssemblerTest` pins the prefix stability
 * that makes a hit possible in the first place.
 */
class PromptAssembler(private val toolset: ProjectToolset) {

    /**
     * A request for one turn.
     *
     * [projectContext] must be derived only from the project — never from the
     * cursor, the clock, or which file happens to be focused. Those belong in
     * the user turn.
     *
     * [history] is the SDK's own `MessageParam` rather than a type of ours.
     * That is not laziness: a tool-use round trip has to carry `tool_use`,
     * `tool_result` and — because thinking is adaptive — `thinking` blocks back
     * to the API **unchanged**, and a role-plus-text record of the conversation
     * silently drops all three. See `AiSession`, which replays whole assistant
     * messages for exactly that reason.
     */
    fun request(
        projectContext: String,
        history: List<MessageParam>,
        effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
        maxTokens: Long = DEFAULT_MAX_TOKENS,
        instructions: String = STANDING_INSTRUCTIONS,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(maxTokens)
            .thinking(ThinkingConfigParam.ofAdaptive(ThinkingConfigAdaptive.builder().build()))
            .outputConfig(OutputConfig.builder().effort(effort).build())
            .tools(toolset.definitions().map(ToolUnion::ofTool))
            .systemOfTextBlockParams(systemBlocks(instructions, projectContext))
            // Passed as a list rather than left to default, because the builder
            // treats messages as required and throws on build() otherwise. An
            // empty conversation is still invalid to the API -- but that is the
            // API's judgement to make, with an error naming the problem, rather
            // than an IllegalStateException saying only "`messages` is
            // required" while the caller is looking at prompt layout.
            .messages(history)

        return builder.build()
    }

    /**
     * The system prompt, in two blocks with the breakpoint between them.
     *
     * Two rather than one because the breakpoint marks a boundary, and a single
     * concatenated block would put the standing instructions and the project
     * context in the same cache entry — so every project change would also
     * re-cache text that never varies. Splitting costs nothing and keeps the
     * stable half stable.
     */
    private fun systemBlocks(
        instructions: String,
        projectContext: String,
    ): List<TextBlockParam> = listOf(
        TextBlockParam.builder().text(instructions).build(),
        TextBlockParam.builder()
            .text(contextBlock(projectContext))
            // The last breakpoint. Everything after this — the whole
            // conversation — is expected to change every turn.
            .cacheControl(CacheControlEphemeral.builder().build())
            .build(),
    )

    private fun contextBlock(projectContext: String): String =
        "Here is the project you are working in.\n\n$projectContext"

    private companion object {
        const val MODEL = "claude-opus-5"

        /** Streaming is used for everything, so this can be generous. */
        const val DEFAULT_MAX_TOKENS = 8_192L

        /**
         * Deliberately short.
         *
         * The tools already describe themselves and when to prefer one over
         * another, and repeating that here would be two sources of truth for
         * the same instruction. What is left is the part the tools cannot say:
         * where the assistant is running, and that it is talking to someone
         * holding a phone.
         */
        val STANDING_INSTRUCTIONS = """
            You are the assistant inside AIDE-OS, an IDE that runs on the user's
            Android device. Builds, compilation and every file you can see are
            local to that device.

            Read before you write. You have tools to list, read and search the
            project; use them rather than guessing at a file's contents or
            inventing a path.

            The user is on a phone. Answer in as few words as the question
            allows, put code in fenced blocks, and give a file path with any
            change you suggest so they can find it.
        """.trimIndent()
    }
}
