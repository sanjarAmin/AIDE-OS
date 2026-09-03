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
    /** Carried rather than re-derived from the name -- risk has one owner. */
    val risk: ToolRisk,
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
 * Supports both Anthropic client (with verbatim thinking replay and prompt caching)
 * and the unified [AiClient] for Gemini, OpenAI, and Custom providers.
 */
class AiSession(
    private val client: AnthropicClient?,
    private val assembler: PromptAssembler?,
    private val aiClient: AiClient?,
    private val toolset: ProjectToolset,
    private val approver: Approver,
    private val dispatchers: DispatcherProvider,
    private val maxToolRounds: Int = 12,
) {

    /** Primary constructor for Anthropic client compatibility. */
    constructor(
        client: AnthropicClient,
        assembler: PromptAssembler,
        toolset: ProjectToolset,
        approver: Approver,
        dispatchers: DispatcherProvider,
        maxToolRounds: Int = 12,
    ) : this(
        client = client,
        assembler = assembler,
        aiClient = null,
        toolset = toolset,
        approver = approver,
        dispatchers = dispatchers,
        maxToolRounds = maxToolRounds,
    )

    /** Constructor for unified [AiClient] (Gemini, OpenAI, Custom). */
    constructor(
        aiClient: AiClient,
        toolset: ProjectToolset,
        approver: Approver,
        dispatchers: DispatcherProvider,
        maxToolRounds: Int = 12,
    ) : this(
        client = null,
        assembler = null,
        aiClient = aiClient,
        toolset = toolset,
        approver = approver,
        dispatchers = dispatchers,
        maxToolRounds = maxToolRounds,
    )

    private val anthropicMessages = mutableListOf<MessageParam>()
    private val genericMessages = mutableListOf<AiMessage>()

    val history: List<Any>
        get() = if (client != null) anthropicMessages.toList() else genericMessages.toList()

    suspend fun send(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
    ): Reply {
        return if (client != null && assembler != null) {
            sendAnthropic(projectContext, userText, effort)
        } else if (aiClient != null) {
            sendGeneric(projectContext, userText, effort)
        } else {
            Reply("No AI client configured.", emptyList())
        }
    }

    private suspend fun sendAnthropic(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort,
    ): Reply {
        anthropicMessages += userTurn(userText)
        val runs = mutableListOf<ToolRun>()

        repeat(maxToolRounds) {
            val response = withContext(dispatchers.io) {
                client!!.messages().create(assembler!!.request(projectContext, anthropicMessages, effort))
            }

            anthropicMessages += response.toParam()

            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (calls.isEmpty()) return Reply(response.textOnly(), runs)

            val results = calls.map { call ->
                val run = executeTool(call.name(), call.inputAsStrings())
                runs += run
                result(call.id(), run.outcome)
            }

            anthropicMessages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build()
        }

        return Reply(
            text = "I stopped after $maxToolRounds rounds of tool calls without finishing. " +
                "Ask me to continue if that looked like progress.",
            toolRuns = runs,
            truncated = true,
        )
    }

    private suspend fun sendGeneric(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort,
    ): Reply {
        genericMessages += AiMessage(AiRole.USER, userText)
        val runs = mutableListOf<ToolRun>()

        val instructions = buildString {
            append("You are Gemini/AI assistant inside AIDE-OS, an IDE that runs on the user's Android device.\n")
            append("You can list, read, search, and edit files in the project.\n")
            append("Here is the project structure and context:\n\n")
            append(projectContext)
        }

        repeat(maxToolRounds) {
            val request = AiClientRequest(
                systemInstruction = instructions,
                messages = genericMessages,
                tools = toolset.all(),
                model = aiClient!!.model,
                effort = effort,
            )

            val response = aiClient.send(request)

            val modelMessage = AiMessage(
                role = AiRole.ASSISTANT,
                parts = response.parts,
            )
            genericMessages += modelMessage

            val calls = response.functionCalls
            if (calls.isEmpty()) {
                return Reply(response.text, runs)
            }

            val results = mutableListOf<AiPart.FunctionResponse>()
            for (call in calls) {
                val run = executeTool(call.name, call.args)
                runs += run

                val content = when (val outcome = run.outcome) {
                    is ProjectFiles.Outcome.Ok -> outcome.content
                    is ProjectFiles.Outcome.Refused -> outcome.reason
                }
                results += AiPart.FunctionResponse(
                    id = call.id,
                    name = call.name,
                    content = content,
                    isError = run.outcome is ProjectFiles.Outcome.Refused,
                )
            }

            genericMessages += AiMessage(
                role = AiRole.USER,
                parts = results,
            )
        }

        return Reply(
            text = "I stopped after $maxToolRounds rounds of tool calls without finishing. " +
                "Ask me to continue if that looked like progress.",
            toolRuns = runs,
            truncated = true,
        )
    }

    private suspend fun executeTool(name: String, input: Map<String, String>): ToolRun {
        val risk = toolset.find(name)?.risk ?: ToolRisk.READ_ONLY
        val approved = risk == ToolRisk.MUTATING && approver.approve(name, input)

        return ToolRun(
            name = name,
            input = input,
            risk = risk,
            approved = approved,
            outcome = toolset.execute(name, input, approved),
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

private fun Message.textOnly(): String = content()
    .mapNotNull { it.text().orElse(null)?.text() }
    .joinToString("\n")
    .trim()

private fun ToolUseBlock.inputAsStrings(): Map<String, String> {
    val raw = runCatching { _input().convert(Map::class.java) }.getOrNull() ?: return emptyMap()
    return raw.entries.mapNotNull { (key, value) ->
        if (key is String && value != null) key to value.toString() else null
    }.toMap()
}
