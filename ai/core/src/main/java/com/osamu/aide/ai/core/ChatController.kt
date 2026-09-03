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
    /** True when there is no API key or sign-in for the active provider. */
    val needsKey: Boolean = false,
    val activeProvider: AiProviderType = AiProviderType.GEMINI,
    val activeModel: String = "gemini-3.7-flash",
    val isGoogleSignedIn: Boolean = false,
    val userEmail: String? = null,
    val shareProjectContext: Boolean = true,
)

/**
 * The chat panel's state and controller.
 */
class ChatController(
    private val assistant: Assistant,
    val projectDir: File,
    private val scope: CoroutineScope,
    /** Contributed by the app layer -- the build tools. See [Assistant.session]. */
    private val extraTools: List<AideTool> = emptyList(),
    private val keys: ApiKeyStore? = null,
) {

    private fun initialState(): ChatUiState {
        val provider = keys?.activeProvider() ?: AiProviderType.GEMINI
        val model = keys?.activeModel(provider) ?: provider.defaultModel
        return ChatUiState(
            activeProvider = provider,
            activeModel = model,
            isGoogleSignedIn = keys?.isGoogleSignedIn() == true,
            userEmail = keys?.googleUserEmail(),
            shareProjectContext = keys?.shareProjectContext() ?: true,
        )
    }

    private val _state = MutableStateFlow(initialState())
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
            val active = session
                ?: assistant.session(projectDir, ::approve, extraTools)?.also { session = it }
            if (active == null) {
                _state.update { it.copy(sending = false, needsKey = true) }
                return@launch
            }

            val contextString = if (_state.value.shareProjectContext) {
                projectContext(ProjectFiles(projectDir))
            } else {
                "Project context sharing disabled by user preference."
            }

            val reply = runCatching {
                active.send(contextString, message)
            }

            reply.fold(
                onSuccess = { done -> _state.update { it.render(done) } },
                onFailure = { failure ->
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

    fun switchProvider(provider: AiProviderType) {
        keys?.setActiveProvider(provider)
        session = null
        val model = keys?.activeModel(provider) ?: provider.defaultModel
        _state.update {
            it.copy(
                activeProvider = provider,
                activeModel = model,
                isGoogleSignedIn = keys?.isGoogleSignedIn() == true,
                userEmail = keys?.googleUserEmail(),
            )
        }
    }

    fun switchModel(model: String) {
        val provider = _state.value.activeProvider
        keys?.setActiveModel(provider, model)
        session = null
        _state.update { it.copy(activeModel = model) }
    }

    fun toggleShareContext(share: Boolean) {
        keys?.setShareProjectContext(share)
        _state.update { it.copy(shareProjectContext = share) }
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
                failed = !declined && outcome is ProjectFiles.Outcome.Refused,
            )
        }
    }
}
