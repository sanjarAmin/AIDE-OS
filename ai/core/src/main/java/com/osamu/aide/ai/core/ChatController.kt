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

/**
 * One entry in the transcript the user sees.
 *
 * **Every entry carries an id and a timestamp.** The id is what a `LazyColumn`
 * keys on -- without it, a streaming entry that changes text every few
 * milliseconds is recomposed as a *new* item, which loses the expansion state
 * of the tool cards above it and re-runs their enter animation. It is also how
 * a message action names its target: "regenerate" has to identify a message,
 * and positional indices shift under a live stream.
 */
sealed interface ChatEntry {
    val id: Long
    val at: Long

    data class FromUser(
        val text: String,
        override val id: Long = nextId(),
        override val at: Long = System.currentTimeMillis(),
    ) : ChatEntry

    data class FromAssistant(
        val text: String,
        override val id: Long = nextId(),
        override val at: Long = System.currentTimeMillis(),
        /**
         * True while tokens are still arriving into this entry.
         *
         * The panel draws a caret and suppresses the message actions: offering
         * Copy on a half-written answer copies half an answer, and Regenerate
         * on a message still being generated is a race.
         */
        val streaming: Boolean = false,
        /**
         * Who wrote it, for a transcript that outlives the provider switch.
         *
         * A conversation can hold answers from two providers -- switching
         * mid-chat is a normal thing to do when a local model struggles -- and
         * without this the history would attribute all of them to whichever is
         * selected now.
         */
        val provider: AiProviderType? = null,
        val model: String? = null,
    ) : ChatEntry

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
        override val id: Long = nextId(),
        override val at: Long = System.currentTimeMillis(),
        /** How long it took, so a slow tool can say so on its card. */
        val durationMs: Long = 0,
    ) : ChatEntry

    companion object {
        private var counter = 0L

        /**
         * Monotonic within a process, which is all a list key needs.
         *
         * Deliberately not `System.nanoTime()`: two entries created in the same
         * nanosecond -- a tool card and the prose after it, on a fast path --
         * would collide, and duplicate keys throw in a `LazyColumn`.
         */
        @Synchronized
        fun nextId(): Long = ++counter
    }
}

/**
 * How long an approval lasts.
 *
 * **Because answering the same question forty times is how a person learns to
 * tap Allow without reading it.** The middle option is the one that makes the
 * feature usable without making approval meaningless: it lasts for this
 * conversation and is forgotten with it, so a session of heavy editing is one
 * decision and tomorrow is a fresh one.
 */
enum class ApprovalScope {
    /** This call only. */
    ONCE,

    /** Every later call of the same tool in this conversation. */
    CONVERSATION,
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

    /**
     * Past conversations for this project, newest first.
     *
     * Loaded once when the panel opens and refreshed on save, rather than
     * recomputed per frame: listing it parses every stored file.
     */
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: String? = null,
    /** The active conversation's name, shown in the header once it has one. */
    val activeTitle: String? = null,
    /**
     * True when the last turn can be sent again.
     *
     * Held as state rather than derived in the UI from `entries.last()`,
     * because "the last entry is from the assistant" is not the same question
     * once tool cards sit between the prose and the user's message.
     */
    val canRegenerate: Boolean = false,
    /**
     * Tools the user has allowed for the rest of this conversation.
     *
     * Surfaced to the UI so the approval prompt can say what a second tap
     * would commit to, and so a chat that has been granted something can show
     * it rather than making the user remember.
     */
    val standingApprovals: Set<String> = emptySet(),
) {
    /**
     * The entry currently being written into, if any.
     *
     * The panel draws its caret and the actions skip it; see
     * [ChatEntry.FromAssistant.streaming].
     */
    val streamingEntryId: Long?
        get() = entries.lastOrNull()
            ?.let { it as? ChatEntry.FromAssistant }
            ?.takeIf { it.streaming }
            ?.id
}

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
    /**
     * Where conversations are kept, or null for a harness that wants none.
     *
     * Optional because the unit tests construct this controller directly and
     * should not be writing files to assert a state transition; null means the
     * panel behaves exactly as it did before history existed.
     */
    private val store: ConversationStore? = null,
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

    /**
     * Tools approved for the rest of this conversation.
     *
     * Held here rather than in [ChatUiState] alone because [approve] runs off
     * the main thread inside the tool loop and must not read a snapshot that a
     * recomposition is in the middle of replacing. The state copy exists so the
     * UI can show what has been granted; this one is the authority.
     */
    private val standing = mutableSetOf<String>()

    /** The conversation being written to, created lazily on the first message. */
    private var conversationId: String? = null

    /**
     * Loads the history list. Cheap enough to call when the panel opens.
     */
    fun refreshConversations() {
        val store = store ?: return
        _state.update { it.copy(conversations = store.list()) }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sending) return
        deliver(message, ChatEntry.FromUser(message))
    }

    /**
     * Sends the last user message again, dropping what it produced.
     *
     * **Everything after that message goes**, tool cards included, rather than
     * a second answer being appended below the first. Two answers to one
     * question with the tool calls of both interleaved is unreadable, and it
     * also poisons the next turn: the model would see itself answer twice.
     *
     * The session is rebuilt for the same reason -- its message history still
     * holds the turn being replaced, and sending on top of it asks the model to
     * follow up on the answer the user just rejected.
     */
    fun regenerate() {
        if (_state.value.sending) return
        val entries = _state.value.entries
        val lastUser = entries.indexOfLast { it is ChatEntry.FromUser }
        if (lastUser < 0) return
        val message = (entries[lastUser] as ChatEntry.FromUser).text

        session = null
        deliver(message, null, keep = entries.take(lastUser + 1))
    }

    /**
     * Replaces one of the user's own messages and re-runs from there.
     *
     * The common case is a typo or a question that was too vague, and the
     * alternative -- asking again below -- leaves the bad question in the
     * history where the model keeps reading it.
     */
    fun editAndResend(entryId: Long, newText: String) {
        if (_state.value.sending) return
        val message = newText.trim()
        if (message.isEmpty()) return
        val entries = _state.value.entries
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0 || entries[index] !is ChatEntry.FromUser) return

        session = null
        deliver(message, ChatEntry.FromUser(message), keep = entries.take(index))
    }

    /**
     * One turn, whatever started it.
     *
     * [keep] replaces the transcript when a turn is being redone, and [added]
     * is the user entry to append -- null when regenerating, because the
     * message is already the last thing in [keep].
     */
    private fun deliver(
        message: String,
        added: ChatEntry.FromUser?,
        keep: List<ChatEntry>? = null,
    ) {
        _state.update {
            val base = keep ?: it.entries
            it.copy(
                entries = base + listOfNotNull(added),
                sending = true,
                activeStatus = "Thinking...",
                error = null,
                needsKey = false,
                canRegenerate = false,
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
                    listener = LiveTurn(),
                )
                _state.update { it.render(done) }
                persist()
            } catch (cancellation: CancellationException) {
                // The half-written answer is kept, not discarded. A user who
                // stops a long reply usually wants to read what arrived before
                // they lost patience with it.
                _state.update {
                    it.settle().copy(
                        entries = it.entries.closeStreaming(),
                        pendingApproval = null,
                    )
                }
                persist()
                throw cancellation
            } catch (failure: Throwable) {
                _state.update {
                    it.settle().copy(
                        entries = it.entries.closeStreaming(),
                        pendingApproval = null,
                        error = failure.message ?: failure::class.java.simpleName,
                    )
                }
                persist()
            } finally {
                sendJob = null
            }
        }
    }

    /**
     * Turns one turn's events into transcript entries as they happen.
     *
     * **The transcript is built forwards, not assembled at the end.** Before
     * this, tool cards and the answer all appeared together when the turn
     * finished, so a minute of work looked identical to a hang -- which is
     * exactly what it looked like on the phone with a local model.
     */
    private inner class LiveTurn : TurnListener {
        override fun onStatus(status: String) {
            _state.update { it.copy(activeStatus = status) }
        }

        override fun onTextDelta(delta: String) {
            _state.update { current ->
                val last = current.entries.lastOrNull()
                val entries = if (last is ChatEntry.FromAssistant && last.streaming) {
                    // Appended into the same entry, so the id is stable and the
                    // list does not treat every token as a new item.
                    current.entries.dropLast(1) + last.copy(text = last.text + delta)
                } else {
                    current.entries + ChatEntry.FromAssistant(
                        text = delta,
                        streaming = true,
                        provider = current.activeProvider,
                        model = current.activeModel,
                    )
                }
                current.copy(entries = entries)
            }
        }

        /**
         * Replaces the open bubble with what it should have said.
         *
         * Only the bubble still being written to: earlier ones in the same turn
         * are finished and correct. A blank settlement removes the bubble,
         * which is the case where everything the model produced that round was
         * a tool call it had written out as prose.
         */
        override fun onTextSettled(text: String) {
            _state.update { current ->
                val open = current.entries.lastOrNull() as? ChatEntry.FromAssistant
                if (open == null || !open.streaming || open.text == text) return@update current
                val entries = if (text.isBlank()) {
                    current.entries.dropLast(1)
                } else {
                    current.entries.dropLast(1) + open.copy(text = text)
                }
                current.copy(entries = entries)
            }
        }

        override fun onToolRun(run: ToolRun) {
            // The open bubble is closed first: prose that came before a tool
            // call belongs above the card, and anything after it starts a new
            // bubble. Without this the answer grows around the card.
            _state.update {
                it.copy(entries = it.entries.closeStreaming() + run.asEntry())
            }
        }
    }

    /** Saves the conversation, creating it on first use, and refreshes the list. */
    private fun persist() {
        val store = store ?: return
        val entries = _state.value.entries
        if (entries.isEmpty()) return
        val id = conversationId ?: newConversationId().also { conversationId = it }
        store.save(id, entries, _state.value.activeTitle)
        _state.update {
            it.copy(
                activeConversationId = id,
                activeTitle = it.activeTitle ?: ConversationStore.titleFrom(entries),
                conversations = store.list(),
            )
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
            it.settle().copy(
                entries = it.entries.closeStreaming(),
                pendingApproval = null,
            )
        }
    }

    /**
     * Starts an empty conversation, keeping the one being left.
     *
     * **Saved before it is cleared.** This used to drop the transcript on the
     * floor, so a new chat destroyed the old one -- fine when nothing was
     * stored and a data-loss bug the moment history existed.
     */
    fun newChat() {
        cancelSend()
        persist()
        session = null
        conversationId = null
        standing.clear()
        _state.update {
            it.copy(
                entries = emptyList(),
                sending = false,
                activeStatus = null,
                pendingApproval = null,
                error = null,
                activeConversationId = null,
                activeTitle = null,
                canRegenerate = false,
                standingApprovals = emptySet(),
                conversations = store?.list() ?: it.conversations,
            )
        }
    }

    /**
     * Reopens a stored conversation.
     *
     * **The model's history is not restored, only the transcript.** Rebuilding
     * an `AiSession`'s internal message list from rendered entries cannot be
     * done faithfully -- Anthropic needs its thinking blocks back verbatim with
     * their signatures (`ai/core/FINDINGS.md`), and those are deliberately not
     * shown and so not stored. So a reopened chat reads as it was, and the next
     * message starts a fresh context with the project listing. The alternative
     * -- storing signed thinking blocks to replay later -- is a real feature
     * and a much larger one, and pretending to have it would produce turns the
     * provider rejects.
     */
    fun openConversation(id: String) {
        val store = store ?: return
        cancelSend()
        persist()
        val entries = store.load(id)
        session = null
        conversationId = id
        standing.clear()
        _state.update {
            it.copy(
                entries = entries,
                sending = false,
                activeStatus = null,
                pendingApproval = null,
                error = null,
                activeConversationId = id,
                activeTitle = store.titleOf(id),
                canRegenerate = entries.any { entry -> entry is ChatEntry.FromUser },
                standingApprovals = emptySet(),
                conversations = store.list(),
            )
        }
    }

    fun deleteConversation(id: String) {
        val store = store ?: return
        store.delete(id)
        if (id == conversationId) {
            conversationId = null
            _state.update {
                it.copy(
                    entries = emptyList(),
                    activeConversationId = null,
                    activeTitle = null,
                    canRegenerate = false,
                    conversations = store.list(),
                )
            }
            session = null
        } else {
            _state.update { it.copy(conversations = store.list()) }
        }
    }

    fun renameConversation(id: String, title: String) {
        val store = store ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        store.rename(id, trimmed)
        _state.update {
            it.copy(
                conversations = store.list(),
                activeTitle = if (id == conversationId) trimmed else it.activeTitle,
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

    /**
     * Answers whatever prompt is on screen. No-op if there is none.
     *
     * [scope] records how long a yes lasts. A no is always just a no: a
     * standing *refusal* would leave the model unable to act with no visible
     * reason, and the user with nothing to undo.
     */
    fun resolveApproval(approved: Boolean, scope: ApprovalScope = ApprovalScope.ONCE) {
        val waiting = awaitingUser ?: return
        val request = _state.value.pendingApproval
        awaitingUser = null
        if (approved && scope == ApprovalScope.CONVERSATION && request != null) {
            standing += request.toolName
        }
        _state.update {
            it.copy(pendingApproval = null, standingApprovals = standing.toSet())
        }
        waiting.complete(approved)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /**
     * The approval gate, called from inside the tool loop.
     *
     * **A standing grant short-circuits before the UI is touched.** Putting a
     * prompt on screen and dismissing it immediately produces a visible flash
     * on every call, and worse, races the recomposition that is still drawing
     * the previous one.
     */
    private suspend fun approve(toolName: String, input: Map<String, String>): Boolean {
        if (toolName in standing) return true

        val answer = CompletableDeferred<Boolean>()
        awaitingUser = answer

        _state.update {
            it.copy(
                // The spinner would otherwise keep claiming the model is
                // thinking while it is in fact waiting for this person.
                activeStatus = "Waiting for you to approve $toolName",
                pendingApproval = ApprovalRequest(
                    toolName = toolName,
                    path = input["path"] ?: input["command"].orEmpty(),
                    preview = (input["content"] ?: input["command"]).orEmpty().take(PREVIEW_CHARS),
                ),
            )
        }
        return answer.await()
    }

    /**
     * Closes the turn.
     *
     * **Tool runs are not appended here.** They arrived through
     * [LiveTurn.onToolRun] as they happened; adding `reply.toolRuns` as well
     * would show every card twice.
     *
     * **[Reply.text] wins over what was streamed**, and only for the open
     * bubble. A local model that wrote its tool call into its prose has that
     * object stripped from the final text (`tools/localai/FINDINGS.md` §4), so
     * trusting the deltas would leave raw JSON on screen; and the round-cap and
     * repeat guards return explanatory text that was never streamed at all.
     * Earlier bubbles in the same turn are left exactly as they streamed,
     * because `reply.text` only ever holds the final block.
     */
    private fun ChatUiState.render(reply: Reply): ChatUiState {
        val open = entries.lastOrNull() as? ChatEntry.FromAssistant
        val entries = when {
            open != null && open.streaming -> {
                val text = reply.text.ifBlank { open.text }
                entries.dropLast(1) + open.copy(text = text, streaming = false)
            }
            // Nothing streamed: a provider with no streaming transport, or a
            // turn that produced only tool calls and then a guard message.
            reply.text.isNotBlank() -> entries + ChatEntry.FromAssistant(
                text = reply.text,
                provider = activeProvider,
                model = activeModel,
            )
            else -> entries
        }
        return copy(
            entries = entries,
            sending = false,
            activeStatus = null,
            pendingApproval = null,
            canRegenerate = entries.any { it is ChatEntry.FromUser },
        )
    }

    /** Puts the panel back at rest without touching the transcript. */
    private fun ChatUiState.settle(): ChatUiState = copy(
        sending = false,
        activeStatus = null,
        canRegenerate = entries.any { it is ChatEntry.FromUser },
    )

    private companion object {
        const val PREVIEW_CHARS = 2_000

        /**
         * Marks the last entry finished, if one was still being written to.
         *
         * Every path out of a turn goes through this -- success, cancel,
         * failure -- because a bubble left `streaming = true` keeps its caret
         * blinking forever and keeps its message actions hidden, which reads as
         * an answer that never finished arriving.
         */
        fun List<ChatEntry>.closeStreaming(): List<ChatEntry> {
            val open = lastOrNull() as? ChatEntry.FromAssistant ?: return this
            if (!open.streaming) return this
            // An entry that received no text at all is dropped rather than left
            // as an empty bubble: a turn cancelled during "Thinking..." would
            // otherwise leave a blank card in the transcript.
            return if (open.text.isBlank()) dropLast(1) else dropLast(1) + open.copy(streaming = false)
        }

        fun newConversationId(): String =
            "c-" + System.currentTimeMillis().toString(36) + "-" + (0..0xFFFF).random().toString(16)

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
                durationMs = durationMs,
            )
        }
    }
}
