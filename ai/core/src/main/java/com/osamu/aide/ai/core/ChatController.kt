package com.osamu.aide.ai.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** One entry in the transcript the user sees. */
sealed interface ChatEntry {
    data class FromUser(val text: String) : ChatEntry
    data class FromAssistant(val text: String) : ChatEntry

    /**
     * A tool the assistant used, shown inline.
     *
     * Rendered rather than hidden because the assistant reads and writes the
     * user's files: "I looked at Main.kt" is the difference between a tool loop
     * the user can audit and one they have to trust.
     */
    data class Tool(
        val name: String,
        val detail: String,
        val declined: Boolean,
        val failed: Boolean,
    ) : ChatEntry
}

/** A mutating tool waiting on the user. */
data class ApprovalRequest(
    val toolName: String,
    val path: String,
    val preview: String,
)

data class ChatUiState(
    val entries: List<ChatEntry> = emptyList(),
    val sending: Boolean = false,
    val pendingApproval: ApprovalRequest? = null,
    val error: String? = null,
    /** True when there is no API key, which is where every new user starts. */
    val needsKey: Boolean = false,
)

/**
 * The chat panel's state, with no Compose in it.
 *
 * Lives in `:ai:core` rather than `:ai:ui` so the parts worth testing — the
 * approval handshake in particular — can be tested against the same local
 * Messages API the session loop uses, instead of through a composition.
 *
 * The approval handshake is the reason this class is not just a wrapper. A
 * mutating tool has to stop the loop, put a prompt on screen, and resume with
 * the user's answer; [AiSession] models that as a suspending [Approver], so
 * here it becomes a state emission plus a [CompletableDeferred] the UI
 * completes. Nothing polls and nothing times out — the loop is genuinely parked
 * until someone answers.
 */
class ChatController(
    private val assistant: Assistant,
    private val projectDir: File,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var session: AiSession? = null
    private var awaitingUser: CompletableDeferred<Boolean>? = null

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sending) return

        _state.update {
            it.copy(
                entries = it.entries + ChatEntry.FromUser(message),
                sending = true,
                error = null,
                needsKey = false,
            )
        }

        scope.launch {
            // Resolved per send rather than in the constructor: the user may add
            // a key in settings after opening the panel, and a panel that stayed
            // keyless until it was closed and reopened would look broken.
            val active = session ?: assistant.session(projectDir, ::approve)?.also { session = it }
            if (active == null) {
                _state.update { it.copy(sending = false, needsKey = true) }
                return@launch
            }

            val reply = runCatching {
                active.send(projectContext(ProjectFiles(projectDir)), message)
            }

            reply.fold(
                onSuccess = { done -> _state.update { it.render(done) } },
                onFailure = { failure ->
                    // The session keeps its history, so the user can retry
                    // without losing the conversation. Only this turn is lost.
                    _state.update {
                        it.copy(
                            sending = false,
                            pendingApproval = null,
                            error = failure.message ?: failure::class.java.simpleName,
                        )
                    }
                },
            )
        }
    }

    /** Answers whatever prompt is on screen. No-op if there is none. */
    fun resolveApproval(approved: Boolean) {
        val waiting = awaitingUser ?: return
        awaitingUser = null
        _state.update { it.copy(pendingApproval = null) }
        waiting.complete(approved)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun approve(toolName: String, input: Map<String, String>): Boolean {
        val answer = CompletableDeferred<Boolean>()
        awaitingUser = answer

        _state.update {
            it.copy(
                pendingApproval = ApprovalRequest(
                    toolName = toolName,
                    path = input["path"].orEmpty(),
                    preview = input["content"].orEmpty().take(PREVIEW_CHARS),
                ),
            )
        }
        return answer.await()
    }

    private fun ChatUiState.render(reply: Reply): ChatUiState = copy(
        entries = entries +
            reply.toolRuns.map { it.asEntry() } +
            ChatEntry.FromAssistant(reply.text),
        sending = false,
        pendingApproval = null,
    )

    private companion object {
        const val PREVIEW_CHARS = 2_000

        fun ToolRun.asEntry(): ChatEntry.Tool {
            val declined = risk == ToolRisk.MUTATING && !approved
            return ChatEntry.Tool(
                name = name,
                detail = input["path"] ?: input["query"] ?: "",
                declined = declined,
                // A refusal the user did not cause is a failure worth showing:
                // a bad path, a file too large. Silently rendering it as a
                // successful call is how the user ends up believing the
                // assistant read something it never saw.
                failed = !declined && outcome is ProjectFiles.Outcome.Refused,
            )
        }
    }
}
