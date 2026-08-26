package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.withContext

/**
 * Asked before a tool that can change the project runs.
 *
 * Suspending, because the honest implementation puts a dialog on screen and
 * waits. Returning `false` is a normal outcome, not an error: the model is told
 * the user declined and carries on with the conversation.
 */
fun interface Approver {
    suspend fun approve(toolName: String, input: Map<String, String>): Boolean
}

/** What one tool call did, for the UI to show alongside the answer. */
data class ToolRun(
    val name: String,
    val input: Map<String, String>,
    val approved: Boolean,
    val outcome: ProjectFiles.Outcome,
)

/** The end of one user turn. */
data class Reply(
    val text: String,
    val toolRuns: List<ToolRun>,
    /** True when the round cap stopped the loop rather than the model did. */
    val truncated: Boolean = false,
)

/**
 * One conversation with the assistant, and the tool loop that drives it.
 *
 * The loop is the standard one — send, look at `stop_reason`, run whatever
 * tools were asked for, send the results back — with three details that are
 * easy to get wrong and produce no visible failure until much later:
 *
 * 1. **The assistant's turn is replayed whole.** [Message.toParam] rather than
 *    a reconstruction from the text, because thinking is adaptive and thinking
 *    blocks must go back to the API *unchanged*. Rebuilding the turn as plain
 *    text drops them, and the next request fails on block ordering rather than
 *    on anything that names the cause.
 * 2. **Every tool result goes in one user message.** A response may hold
 *    several `tool_use` blocks; splitting the results across messages teaches
 *    the model to stop asking for parallel calls, which shows up as a slower
 *    assistant and nothing else.
 * 3. **Every `tool_use` gets a `tool_result`, including the refused ones.** A
 *    missing result for a call the model made is a 400. A refusal is a result
 *    with `is_error` -- information the model can act on, not a hole.
 *
 * State is held here rather than passed in because a conversation is a
 * conversation; [history] is exposed read-only for the UI to render.
 */
class AiSession(
    private val client: AnthropicClient,
    private val assembler: PromptAssembler,
    private val toolset: ProjectToolset,
    private val approver: Approver,
    private val dispatchers: DispatcherProvider,
    /**
     * How many times the model may call tools before the turn is cut off.
     *
     * A cap rather than a timeout, because the failure this guards against is a
     * loop that spends the *user's own* money -- read, edit, read the edit,
     * edit again -- and every round of it looks like progress. Generous enough
     * that a genuine multi-file change fits.
     */
    private val maxToolRounds: Int = 12,
) {

    private val messages = mutableListOf<MessageParam>()

    val history: List<MessageParam> get() = messages.toList()

    /**
     * Sends one user message and runs the tool loop until the model is done.
     *
     * [projectContext] is re-supplied per turn so a file created mid-session is
     * visible on the next one -- and it belongs to the cached prefix, so it
     * must stay derived from the project alone. See [PromptAssembler].
     */
    suspend fun send(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
    ): Reply {
        messages += userTurn(userText)

        val runs = mutableListOf<ToolRun>()

        repeat(maxToolRounds) {
            val response = withContext(dispatchers.io) {
                client.messages().create(assembler.request(projectContext, messages, effort))
            }

            // Detail 1: the whole turn, blocks intact.
            messages += response.toParam()

            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (calls.isEmpty()) return Reply(response.textOnly(), runs)

            val results = calls.map { call ->
                val run = execute(call)
                runs += run
                result(call.id(), run.outcome)
            }

            // Detail 2 and 3: one message, one result per call.
            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build()
        }

        // The cap was hit. The last assistant turn is already in the history, so
        // the conversation stays valid and the user can simply say "carry on".
        return Reply(
            text = "I stopped after $maxToolRounds rounds of tool calls without finishing. " +
                "Ask me to continue if that looked like progress.",
            toolRuns = runs,
            truncated = true,
        )
    }

    private suspend fun execute(call: ToolUseBlock): ToolRun {
        val input = call.inputAsStrings()
        val mutating = toolset.find(call.name())?.risk == ToolRisk.MUTATING
        val approved = mutating && approver.approve(call.name(), input)

        return ToolRun(
            name = call.name(),
            input = input,
            approved = approved,
            // Approval is passed through rather than acted on here: ProjectToolset
            // refuses a mutating tool without it regardless, so a bug in this
            // method fails closed instead of writing to the project.
            outcome = toolset.execute(call.name(), input, approved),
        )
    }

    private fun result(toolUseId: String, outcome: ProjectFiles.Outcome): ContentBlockParam {
        val block = ToolResultBlockParam.builder().toolUseId(toolUseId)
        return ContentBlockParam.ofToolResult(
            when (outcome) {
                is ProjectFiles.Outcome.Ok -> block.content(outcome.content).build()
                is ProjectFiles.Outcome.Refused ->
                    block.content(outcome.reason).isError(true).build()
            },
        )
    }
}

/**
 * The visible answer, with thinking and tool calls left out.
 *
 * Concatenated rather than taking the first text block: with adaptive thinking
 * the model may interleave thinking and text, so a response can hold several
 * text blocks, and taking `first()` shows the user a fragment of their answer.
 */
private fun Message.textOnly(): String = content()
    .mapNotNull { it.text().orElse(null)?.text() }
    .joinToString("\n")
    .trim()

/**
 * The tool's arguments, flattened to strings.
 *
 * The tools declare every parameter as `"type": "string"`, but the model still
 * occasionally sends a number or a boolean where a string was asked for, and
 * `input` is untyped JSON either way. Coercing here keeps that out of every
 * handler.
 */
private fun ToolUseBlock.inputAsStrings(): Map<String, String> {
    val raw = runCatching { _input().convert(Map::class.java) }.getOrNull() ?: return emptyMap()
    return raw.entries.mapNotNull { (key, value) ->
        if (key is String && value != null) key to value.toString() else null
    }.toMap()
}
