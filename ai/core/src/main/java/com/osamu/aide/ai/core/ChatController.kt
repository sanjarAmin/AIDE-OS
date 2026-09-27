package com.osamu.aide.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        /**
         * A transcript as the alternating turns [AiSession.seed] takes.
         *
         * The transcript is not already that shape. One answer is several
         * bubbles when tool cards interrupt it, so neighbouring assistant text is
         * joined; tool cards carry no words and are skipped (see `seed`). A
         * stopped turn can leave a question with no answer, and two user turns
         * in a row would be merged by some providers and rejected by others --
         * so it gets an answer that says what happened, which is also true.
         */
        fun priorTurns(entries: List<ChatEntry>): List<PriorTurn> {
            val turns = mutableListOf<PriorTurn>()
            for (entry in entries) {
                val (fromUser, text) = when (entry) {
                    is FromUser -> true to entry.text
                    is FromAssistant -> false to entry.text
                    is Tool -> continue
                }
                if (text.isBlank()) continue
                val last = turns.lastOrNull()
                when {
                    // A conversation starts with the user; an assistant turn
                    // before any question has nothing to answer.
                    last == null && !fromUser -> continue
                    last != null && last.fromUser == fromUser && !fromUser ->
                        turns[turns.lastIndex] = last.copy(text = last.text + "\n\n" + text)
                    last != null && last.fromUser && fromUser -> {
                        turns += PriorTurn(fromUser = false, text = NO_ANSWER)
                        turns += PriorTurn(fromUser = true, text = text)
                    }
                    else -> turns += PriorTurn(fromUser, text)
                }
            }
            if (turns.lastOrNull()?.fromUser == true) turns += PriorTurn(fromUser = false, text = NO_ANSWER)
            return turns
        }

        /** Stands in for an answer that was stopped before it produced any text. */
        const val NO_ANSWER = "(No answer: this turn was stopped before I replied.)"
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
    /** The command, for a tool that runs one. Blank for a file write. */
    val preview: String,
    /**
     * The change, for a tool that writes a file: rows against what is on disk
     * now. Null for anything else; empty when the write would change nothing.
     */
    val diff: List<DiffLine>? = null,
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
    /**
     * Where the approval prompt reads the file it diffs against. Injectable
     * because a test's virtual clock does not wait for the real IO pool, and
     * the prompt would appear after the test had already looked for it.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
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
     * Which turn is live. Bumped by every new turn and by [cancelSend].
     *
     * **Cancelling a turn does not stop it.** The request is a blocking call on
     * an IO thread, and coroutine cancellation is only noticed at a suspension
     * point -- so a stopped answer went on streaming into the transcript, and a
     * question sent straight after it ran concurrently on the same session.
     * Each turn's callbacks and clean-up check this against the number they
     * were started with, and a stale one stops rather than writing to the
     * panel. Volatile because the streaming callbacks arrive on an IO thread.
     */
    @Volatile
    private var liveTurn = 0L

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

    private val written = Channel<String>(Channel.BUFFERED)

    /**
     * Project-relative paths the assistant has just changed.
     *
     * **Because a tool writing a file is invisible to the editor.** The toolset
     * writes through `ProjectFiles`, which knows nothing of open buffers, so an
     * approved `edit_file` on the file you are looking at left the old text on
     * screen -- and the next keystroke would have saved it back over the
     * assistant's work. The workspace listens here and re-reads.
     *
     * A channel rather than state: this is an event, and a state field would
     * replay the last write to every new collector.
     */
    val filesWritten: Flow<String> = written.receiveAsFlow()

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
        // What the model should already know when this message arrives: the
        // transcript up to the message, not including it. Only used when the
        // session is rebuilt -- a live one already holds it, with its tool calls.
        val before = keep ?: _state.value.entries
        val history = if (added == null) before.dropLast(1) else before
        val turn = ++liveTurn
        // Captured now, not read when the reply lands: the provider can be
        // switched while this turn is still answering, and the reply was
        // written by the one that was active when it started.
        val provider = _state.value.activeProvider
        val model = _state.value.activeModel

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
                ?: assistant.session(projectDir, ::approve, extraTools)?.also {
                    it.seed(ChatEntry.priorTurns(history))
                    session = it
                }
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
                    listener = LiveTurn(turn, provider, model),
                )
                if (turn != liveTurn) return@launch
                _state.update { it.render(done, provider, model) }
                persist()
            } catch (cancellation: CancellationException) {
                // A stale turn unwinding late must not settle the one that
                // replaced it: this ran after the next question had been sent,
                // and marked it finished while it was still being answered.
                if (turn != liveTurn) throw cancellation
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
                if (turn != liveTurn) return@launch
                _state.update {
                    it.settle().copy(
                        entries = it.entries.closeStreaming(),
                        pendingApproval = null,
                        error = failure.message ?: failure::class.java.simpleName,
                    )
                }
                persist()
            } finally {
                // Only its own: a stale turn finishing late would otherwise
                // forget the live one, and Stop would then do nothing.
                if (sendJob === currentCoroutineContext()[Job]) sendJob = null
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
    private inner class LiveTurn(
        private val turn: Long,
        private val provider: AiProviderType?,
        private val model: String?,
    ) : TurnListener {
        /**
         * Stops a turn that is no longer live.
         *
         * Thrown from inside the provider's read loop, which is the one place
         * a blocking stream can be interrupted from: the exception unwinds
         * through the reader, which closes the response and drops the
         * connection, instead of the answer arriving in full into a panel
         * that has moved on.
         */
        private fun ensureLive() {
            if (turn != liveTurn) throw CancellationException("turn $turn was stopped")
        }

        override fun onStatus(status: String) {
            ensureLive()
            _state.update { it.copy(activeStatus = status) }
        }

        override fun onTextDelta(delta: String) {
            ensureLive()
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
                        provider = provider,
                        model = model,
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
            ensureLive()
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
            ensureLive()
            // Only a change that actually landed: a declined call and a refused
            // one both leave the file as it was, and re-reading for them would
            // flash a highlight over nothing.
            if (run.risk == ToolRisk.MUTATING &&
                run.approved &&
                run.outcome is ProjectFiles.Outcome.Ok
            ) {
                run.input["path"]?.takeIf { it.isNotBlank() }?.let(written::trySend)
            }
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
        liveTurn++
        // The stopped turn may still be using this session from its IO
        // thread. Dropping it means the next message builds a fresh one,
        // seeded from the transcript -- including whatever the stopped answer
        // had written, which the model then sees as its own.
        session = null
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
        // A stopped turn can reach here before it notices it was stopped, and
        // would put a prompt in front of the person for a turn they cancelled.
        currentCoroutineContext().ensureActive()
        if (toolName in standing) return true

        val path = input["path"]
        val content = input["content"]
        // Any tool that writes a path with new content is a file write, and
        // the question it asks is what changes -- not what the file will say.
        val diff = if (path != null && content != null) {
            withContext(io) { changeTo(path, content) }
        } else {
            null
        }

        val answer = CompletableDeferred<Boolean>()
        awaitingUser = answer

        _state.update {
            it.copy(
                // The spinner would otherwise keep claiming the model is
                // thinking while it is in fact waiting for this person.
                activeStatus = "Waiting for you to approve $toolName",
                pendingApproval = ApprovalRequest(
                    toolName = toolName,
                    path = path ?: input["command"].orEmpty(),
                    preview = if (diff == null) input["command"].orEmpty().take(PREVIEW_CHARS) else "",
                    diff = diff,
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
    /**
     * What writing [content] to [path] would change.
     *
     * Read through [ProjectFiles.resolve], so a path the tool would refuse is
     * not read here either; the tool reports the refusal after approval.
     */
    private fun changeTo(path: String, content: String): List<DiffLine> {
        val file = ProjectFiles(projectDir).resolve(path)?.takeIf { it.isFile }
            ?: return LineDiff.of(null, content)
        if (file.length() > MAX_DIFF_BYTES) {
            return listOf(
                DiffLine(
                    DiffLine.Kind.GAP,
                    "The file is too large to compare. This is its new content in full.",
                    null,
                ),
            ) + LineDiff.of(null, content)
        }
        val old = runCatching { file.readText() }.getOrNull() ?: return LineDiff.of(null, content)
        return LineDiff.of(old, content)
    }

    private fun ChatUiState.render(
        reply: Reply,
        provider: AiProviderType?,
        model: String?,
    ): ChatUiState {
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
                provider = provider,
                model = model,
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

        /** Past this, comparing costs more than the prompt is worth. */
        const val MAX_DIFF_BYTES = 1_024L * 1_024

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
