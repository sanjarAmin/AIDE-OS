package com.osamu.aide.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
        val input: Map<String, String> = emptyMap(),
        val result: String? = null,
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
    val activeStatus: String? = null,
    val pendingApproval: ApprovalRequest? = null,
    val error: String? = null,
    /**
     * True when the active provider cannot be used yet: no key, or for Custom,
     * no address. See `ApiKeyStore.isReady`.
     */
    val needsKey: Boolean = false,
    /**
     * The providers that already hold a key.
     *
     * The picker offers four and the user cannot see which of them will work,
     * so switching to an unconfigured one used to look like it succeeded and
     * failed on the next message instead. Holding the whole set lets the menu
     * say so before the switch.
     */
    val providersWithKeys: Set<AiProviderType> = emptySet(),
    val activeProvider: AiProviderType = AiProviderType.GEMINI,
    val activeModel: String = AiProviderType.DEFAULT.defaultModel,
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
            needsKey = keys != null && !keys.isReady(provider),
            providersWithKeys = configuredProviders(),
            isGoogleSignedIn = keys?.isGoogleSignedIn() == true,
            userEmail = keys?.googleUserEmail(),
            shareProjectContext = keys?.shareProjectContext() ?: true,
        )
    }

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var session: AiSession? = null
    private var awaitingUser: CompletableDeferred<Boolean>? = null
    private var sendJob: Job? = null

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sending) return

        _state.update {
            it.copy(
                entries = it.entries + ChatEntry.FromUser(message),
                sending = true,
                activeStatus = "Thinking...",
                error = null,
                needsKey = false,
            )
        }

        sendJob = scope.launch {
            val active = session
                ?: assistant.session(projectDir, ::approve, extraTools)?.also { session = it }
            if (active == null) {
                _state.update { it.copy(sending = false, activeStatus = null, needsKey = true) }
                return@launch
            }

            val contextString = if (_state.value.shareProjectContext) {
                projectContext(ProjectFiles(projectDir))
            } else {
                "Project context sharing disabled by user preference."
            }

            try {
                val done = active.send(
                    projectContext = contextString,
                    userText = message,
                    onStatus = { status ->
                        _state.update { it.copy(activeStatus = status) }
                    },
                )
                _state.update { it.render(done) }
            } catch (cancellation: CancellationException) {
                _state.update {
                    it.copy(
                        sending = false,
                        activeStatus = null,
                        pendingApproval = null,
                    )
                }
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        sending = false,
                        activeStatus = null,
                        pendingApproval = null,
                        error = failure.message ?: failure::class.java.simpleName,
                    )
                }
            } finally {
                sendJob = null
            }
        }
    }

    fun cancelSend() {
        sendJob?.cancel()
        sendJob = null
        awaitingUser?.let {
            it.complete(false)
            awaitingUser = null
        }
        _state.update {
            it.copy(
                sending = false,
                activeStatus = null,
                pendingApproval = null,
            )
        }
    }

    fun newChat() {
        cancelSend()
        session = null
        _state.update {
            it.copy(
                entries = emptyList(),
                sending = false,
                activeStatus = null,
                pendingApproval = null,
                error = null,
            )
        }
    }

    /**
     * Without a store there is nothing to ask, and claiming every provider is
     * unconfigured would put a key prompt in front of a test harness that has
     * no keys by design.
     */
    private fun configuredProviders(): Set<AiProviderType> =
        keys?.let { store -> AiProviderType.entries.filter(store::isReady).toSet() }
            ?: AiProviderType.entries.toSet()

    fun switchProvider(provider: AiProviderType) {
        keys?.setActiveProvider(provider)
        session = null
        val model = keys?.activeModel(provider) ?: provider.defaultModel
        _state.update {
            it.copy(
                activeProvider = provider,
                activeModel = model,
                // Recomputed on the switch, not left to the next send to
                // discover: the prompt belongs to the moment the provider was
                // chosen, which is when the user can still change their mind.
                needsKey = keys != null && !keys.isReady(provider),
                providersWithKeys = configuredProviders(),
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
                    path = input["path"] ?: input["command"].orEmpty(),
                    preview = (input["content"] ?: input["command"]).orEmpty().take(PREVIEW_CHARS),
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
        activeStatus = null,
        pendingApproval = null,
    )

    private companion object {
        const val PREVIEW_CHARS = 2_000

        fun ToolRun.asEntry(): ChatEntry.Tool {
            val declined = risk == ToolRisk.MUTATING && !approved
            val output = when (outcome) {
                is ProjectFiles.Outcome.Ok -> outcome.content
                is ProjectFiles.Outcome.Refused -> outcome.reason
            }
            return ChatEntry.Tool(
                name = name,
                detail = input["path"] ?: input["query"] ?: input["command"] ?: "",
                declined = declined,
                failed = !declined && outcome is ProjectFiles.Outcome.Refused,
                input = input,
                result = output,
            )
        }
    }
}
