package com.osamu.aide.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.helpers.MessageAccumulator
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

/**
 * Live progress for one turn, for a UI that shows work as it happens.
 *
 * **Why an interface and not three more lambda parameters.** A turn produces
 * three kinds of event and a caller almost always wants all of them or none:
 * prose as it is generated, tools as they finish, and a short status for the
 * spinner. Passing them separately meant the one caller that needed live tool
 * cards had to thread four nullable lambdas through two loops.
 *
 * **Order is part of the contract.** Deltas and tool runs interleave in the
 * order the model produced them, so a caller can render a transcript by
 * appending: prose, then the tool it decided to call, then the prose that
 * followed the result. Getting this wrong puts the answer above the work that
 * produced it.
 *
 * Every method has a default, so a caller overrides only what it renders.
 */
interface TurnListener {
    /** A short imperative line for a spinner: "Running read_file (a.kt)...". */
    fun onStatus(status: String) {}

    /**
     * Prose as it arrives.
     *
     * Not the whole reply and not a prefix of it -- one fragment, to append.
     * [Reply.text] remains authoritative at the end of the turn: a local model
     * that wrote a tool call into its prose has that object stripped from the
     * final text, so a UI that trusted only the deltas would leave the JSON on
     * screen. `tools/localai/FINDINGS.md` §4.
     */
    fun onTextDelta(delta: String) {}

    /**
     * A tool that has finished, approved or refused.
     *
     * Fires before any prose that follows it, which is what lets a transcript
     * close the current assistant bubble and open a new one after the card.
     */
    fun onToolRun(run: ToolRun) {}

    /**
     * The authoritative text of the block that has just finished streaming.
     *
     * **Because the deltas are not always what should be on screen.** A local
     * model that writes its tool call into its prose has that object stripped
     * from the response (`tools/localai/FINDINGS.md` §4) -- but the raw JSON
     * has already streamed, and when a tool call follows in the same round the
     * bubble is closed before the end of the turn could correct it. Driving the
     * phone showed the result: a permanent code block holding
     * `{"name": "read_file", ...}` sitting above the answer.
     *
     * Fires once per round, after the response arrives and before any tool
     * runs, with the text that block should end up showing. Blank means the
     * block should not be shown at all.
     */
    fun onTextSettled(text: String) {}
}

/** What one tool call did, for the UI to show alongside the answer. */
data class ToolRun(
    val name: String,
    val input: Map<String, String>,
    /** Carried rather than re-derived from the name -- risk has one owner. */
    val risk: ToolRisk,
    val approved: Boolean,
    val outcome: ProjectFiles.Outcome,
    /**
     * How long the tool itself took, excluding any wait for approval.
     *
     * Measured here because this is the only place that knows both ends, and
     * shown on the card so a turn that felt slow can be attributed: a grep over
     * a large project and a model thinking for a minute are the same spinner
     * from the outside.
     *
     * **The approval wait is deliberately not counted.** It was, and driving
     * the phone showed why that was wrong: `ls -la` came back labelled `40.0s`
     * because the clock had been running while a person read the prompt and
     * decided. The card is an account of what the tool cost, and a reader takes
     * the number to mean the command was slow.
     */
    val durationMs: Long = 0,
)

/**
 * One finished turn from before this session existed, replayed as plain text.
 *
 * See [AiSession.seed] for why only the words survive.
 */
data class PriorTurn(val fromUser: Boolean, val text: String)

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

    /**
     * Starts this session part-way through a conversation.
     *
     * **A session used to begin empty every time it was rebuilt**, and it is
     * rebuilt far more often than it looks: regenerating an answer, editing an
     * earlier message, reopening a saved chat, switching model or provider. Each
     * of those showed the whole conversation on screen while the model saw only
     * the next message, so "make it shorter" after a regenerate had nothing to
     * refer to.
     *
     * **Only the words, not the tool calls.** A `tool_use` has to be answered by
     * its `tool_result` in the next message and, with thinking on, the
     * assistant message carrying it must go back with its thinking blocks
     * verbatim -- none of which the transcript keeps. Describing the calls in
     * prose instead would teach a local model that writing a call out as text
     * is how tools are used, which is the failure `tools/localai/FINDINGS.md` §4
     * exists to undo. What the model loses is which files it read; it can read
     * them again.
     *
     * [turns] must alternate, start with the user and end with the assistant --
     * the next [send] adds the user turn. `ChatEntry.priorTurns` shapes a
     * transcript that way.
     */
    fun seed(turns: List<PriorTurn>) {
        check(anthropicMessages.isEmpty() && genericMessages.isEmpty()) {
            "seed() is for a fresh session; this one already has history"
        }
        for (turn in turns) {
            if (client != null) {
                anthropicMessages += if (turn.fromUser) userTurn(turn.text) else assistantTurn(turn.text)
            } else {
                genericMessages += AiMessage(
                    if (turn.fromUser) AiRole.USER else AiRole.ASSISTANT,
                    turn.text,
                )
            }
        }
    }

    suspend fun send(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort = OutputConfig.Effort.HIGH,
        listener: TurnListener? = null,
    ): Reply {
        return if (client != null && assembler != null) {
            sendAnthropic(projectContext, userText, effort, listener)
        } else if (aiClient != null) {
            sendGeneric(projectContext, userText, effort, listener)
        } else {
            Reply("No AI client configured.", emptyList())
        }
    }

    private suspend fun sendAnthropic(
        projectContext: String,
        userText: String,
        effort: OutputConfig.Effort,
        listener: TurnListener? = null,
    ): Reply {
        anthropicMessages += userTurn(userText)
        val runs = mutableListOf<ToolRun>()

        repeat(maxToolRounds) {
            val request = assembler!!.request(projectContext, anthropicMessages, effort)
            val response = withContext(dispatchers.io) {
                if (listener == null) {
                    client!!.messages().create(request)
                } else {
                    // **Accumulated by the SDK's `MessageAccumulator`**, not by
                    // hand. Rebuilding a message from `content_block_start`,
                    // `input_json_delta` and `content_block_stop` means
                    // re-deriving how they compose into a tool call, and getting
                    // it subtly wrong for thinking blocks -- which have to go
                    // back verbatim, signature included, or the next turn is
                    // rejected. Only text deltas are forwarded: streaming
                    // thinking into the bubble shows reasoning as the answer,
                    // then replaces it, which reads as the assistant changing
                    // its mind mid-sentence.
                    val accumulator = MessageAccumulator.create()
                    client!!.messages().createStreaming(request).use { stream ->
                        stream.stream().forEach { event ->
                            accumulator.accumulate(event)
                            event.contentBlockDelta().ifPresent { block ->
                                block.delta().text().ifPresent { delta ->
                                    delta.text().takeIf { it.isNotEmpty() }
                                        ?.let(listener::onTextDelta)
                                }
                            }
                        }
                    }
                    accumulator.message()
                }
            }

            anthropicMessages += response.toParam()
            listener?.onTextSettled(response.textOnly())

            val calls = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (calls.isEmpty()) return Reply(response.textOnly(), runs)

            val results = calls.map { call ->
                val target = call.inputAsStrings()["path"] ?: call.inputAsStrings()["command"] ?: call.inputAsStrings()["query"]
                val statusMsg = if (target != null) "Running ${call.name()} ($target)..." else "Running ${call.name()}..."
                listener?.onStatus(statusMsg)
                val run = executeTool(call.name(), call.inputAsStrings())
                runs += run
                // Announced the moment it finishes, not at the end of the turn.
                // A read that takes ten seconds used to leave the panel showing
                // nothing but a spinner.
                listener?.onToolRun(run)
                result(call.id(), run.outcome)
            }
            listener?.onStatus("Analyzing results...")

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
        listener: TurnListener? = null,
    ): Reply {
        genericMessages += AiMessage(AiRole.USER, userText)
        val runs = mutableListOf<ToolRun>()

        val instructions = buildString {
            append("You are the AI assistant inside AIDE-OS, an IDE that runs on the user's Android device.\n")
            append("You can list, read, search, and edit files in the project.\n\n")

            // **Tools are for reaching things the context does not already
            // hold.** Without saying so, a small model treats the presence of
            // tools as an instruction to use them: asked a bare "hello", Qwen
            // 2.5-Coder 1.5B called `list_files` twelve times and never
            // answered. `tools/localai/FINDINGS.md` §9.
            //
            // **Both directions, and the positive one first.** An earlier
            // version of this listed only when *not* to call a tool, and a
            // 1.5B given nothing but prohibitions became uniformly
            // prohibitive: asked to read MainActivity.java it called nothing
            // and replied "the code is not provided ... so I cannot read the
            // file". That is worse than the loop it replaced -- a loop wastes
            // time, a refusal makes the assistant useless and sounds
            // authoritative doing it. `tools/localai/FINDINGS.md` §9.
            append("Call a tool whenever the user asks you to look at, search or change a file.\n")
            append("You *can* read any file in the project: if you need its contents, call\n")
            append("read_file. Never say you are unable to read a file.\n")
            append("Do not call a tool for something this prompt already contains. Answer\n")
            append("directly, with no tool calls, when the user greets you, asks a general or\n")
            append("conceptual question, asks about the project layout shown below, or wants code\n")
            append("written from what you can already see.\n")
            append("Never repeat a call you have already made with the same arguments; its result\n")
            append("is in the conversation, so use that instead.\n")
            append("When you have enough to answer, stop calling tools and reply in plain text.\n\n")

            if (aiClient?.provider == AiProviderType.LOCAL) {
                // **Blunter, for a model that needs it.** The guidance above is
                // written for something that can weigh it; a 1.5B follows short
                // imperative sentences and a worked example far better than
                // reasoning about necessity. Only the local provider gets this,
                // because for a capable model it is noise that spends context
                // the phone is already short of.
                append("Examples:\n")
                append("- \"hello\" -> reply with a greeting. No tools.\n")
                append("- \"what does this project do?\" -> answer from the structure below. No tools.\n")
                append("- \"what is in MainActivity.java?\" -> call read_file with that path, then\n")
                append("  answer from what it returns.\n")
                append("- \"where is greeting used?\" -> call grep, then answer.\n")
                // No "call at most one tool" ceiling here. It was in an earlier
                // version and it is the line that tipped this model from
                // over-calling into refusing: a cap reads as discouragement,
                // and the dedup guard below already bounds the real risk.
                append("Usually one tool call is enough. Make it, read the result, then answer.\n\n")
            }

            append("Here is the project structure and context:\n\n")
            append(projectContext)
        }

        // **Asking a small model not to repeat itself is not enough.** The
        // instruction above is a request; this is the part that holds. Qwen
        // 2.5-Coder 1.5B answered a bare "hello" with twelve identical
        // `list_files` calls, so a repeated call is answered from here instead
        // of being run again -- the result it wants is already in the
        // conversation, and re-running it would spend a minute of phone CPU to
        // produce a byte-identical string. `tools/localai/FINDINGS.md` §9.
        val executed = mutableSetOf<String>()
        var roundsOfNothingNew = 0

        repeat(maxToolRounds) {
            val request = AiClientRequest(
                systemInstruction = instructions,
                messages = genericMessages,
                tools = toolset.all(),
                model = aiClient!!.model,
                effort = effort,
            )

            val response = if (listener == null) {
                aiClient.send(request)
            } else {
                aiClient.send(request) { delta -> listener.onTextDelta(delta) }
            }

            // Before the tool cards, because a card closes the open bubble and
            // the correction has to reach it first.
            listener?.onTextSettled(response.text)

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
            var ranSomethingNew = false
            for (call in calls) {
                // Name plus arguments, order-independent: the same call written
                // twice with its keys in a different order is the same call.
                val signature = call.name + "\u0000" +
                    call.args.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }

                if (!executed.add(signature)) {
                    // Marked as an error so the model treats it as a correction
                    // rather than data, and told what to do instead -- "already
                    // called" alone leaves a small model free to try again.
                    results += AiPart.FunctionResponse(
                        id = call.id,
                        name = call.name,
                        content = "You already called ${call.name} with these arguments and the " +
                            "result is earlier in this conversation. Do not call it again. " +
                            "Answer the user now using what you already have.",
                        isError = true,
                    )
                    continue
                }

                ranSomethingNew = true
                val target = call.args["path"] ?: call.args["command"] ?: call.args["query"]
                val statusMsg = if (target != null) "Running ${call.name} ($target)..." else "Running ${call.name}..."
                listener?.onStatus(statusMsg)
                val run = executeTool(call.name, call.args)
                runs += run
                // Announced the moment it finishes, not at the end of the turn.
                // A read that takes ten seconds used to leave the panel showing
                // nothing but a spinner.
                listener?.onToolRun(run)

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

            // **One chance to take the correction, then stop.** A round that
            // added nothing new is told so; a second means the model is stuck
            // in a way more rounds will not fix, and twelve of them cost
            // eighteen minutes of CPU on a phone for no answer.
            if (ranSomethingNew) {
                roundsOfNothingNew = 0
            } else {
                roundsOfNothingNew++
                if (roundsOfNothingNew >= 2) {
                    return Reply(
                        text = response.text.ifBlank {
                            "I kept asking for the same thing instead of answering. " +
                                "Try rephrasing, or ask for something more specific."
                        },
                        toolRuns = runs,
                        truncated = true,
                    )
                }
            }
            listener?.onStatus("Analyzing results...")

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
        // After the approval, not before it: see [ToolRun.durationMs].
        val started = System.currentTimeMillis()

        return ToolRun(
            name = name,
            input = input,
            risk = risk,
            approved = approved,
            outcome = toolset.execute(name, input, approved),
            durationMs = System.currentTimeMillis() - started,
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
