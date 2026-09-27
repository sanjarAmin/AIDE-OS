package com.osamu.aide.ai.core

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The transcript as it is built, not as it ends up.
 *
 * **What this is here to catch.** Before streaming, one turn produced one state
 * update at the end, so the only thing worth asserting was the final list. Now
 * the list is mutated per token, and the interesting failures are all about
 * *order and identity*: an entry that gets a new id on every token (which
 * collapses the tool cards above it), prose that grows around a tool card
 * instead of after it, a bubble left marked `streaming` forever, and a
 * cancelled turn that throws away what the user already read.
 *
 * Driven through a scripted [FakeClient] rather than a mock web server, because
 * what is under test is the controller's assembly, and a real transport would
 * only add a way for the test to be slow and flaky.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStreamingTest {

    private lateinit var projectDir: File
    private lateinit var storeRoot: File

    @Before
    fun setUp() {
        projectDir = File.createTempFile("chat-project", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        File(projectDir, "Main.kt").writeText("fun main() {}")
        storeRoot = File.createTempFile("chat-store", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
    }

    /**
     * One scripted turn: prose fragments, then any tool calls to ask for.
     *
     * [settled] is the authoritative text when it differs from the deltas --
     * the written-call case, where the client streams raw JSON and then returns
     * it stripped.
     */
    private class Turn(
        val deltas: List<String>,
        val calls: List<AiPart.FunctionCall> = emptyList(),
        val settled: String? = null,
    )

    private class FakeClient(private val turns: List<Turn>) : AiClient {
        override val provider = AiProviderType.OPENAI
        override val model = "fake"
        var sends = 0
            private set

        override suspend fun send(request: AiClientRequest): AiClientResponse = response(turns[index()])

        override suspend fun send(
            request: AiClientRequest,
            onTextDelta: (String) -> Unit,
        ): AiClientResponse {
            val turn = turns[index()]
            turn.deltas.forEach(onTextDelta)
            return response(turn)
        }

        private fun index(): Int = (sends++).coerceAtMost(turns.lastIndex)

        private fun response(turn: Turn) = AiClientResponse(
            parts = buildList {
                val text = turn.settled ?: turn.deltas.joinToString("")
                text.takeIf { it.isNotEmpty() }?.let { add(AiPart.Text(it)) }
                addAll(turn.calls)
            },
        )

        override suspend fun complete(context: CompletionContext): String? = null
    }

    private fun newController(
        vararg turns: Turn,
        store: ConversationStore? = null,
        approve: Boolean = true,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ChatController {
        val client = FakeClient(turns.toList())
        val assistant = object : Assistant() {
            override fun session(
                projectDir: File,
                approver: Approver,
                extraTools: List<AideTool>,
            ) = AiSession(
                aiClient = client,
                toolset = ProjectToolset(ProjectFiles(projectDir), extraTools),
                approver = Approver { _, _ -> approve },
                dispatchers = unconfinedDispatchers,
            )

            override fun completer(): InlineCompleter? = null
        }
        return ChatController(
            assistant = assistant,
            projectDir = projectDir,
            scope = scope,
            store = store,
            io = Dispatchers.Unconfined,
        )
    }

    /** Real time, not virtual: the card's clock is `System.nanoTime`. */
    private val SLOW_TOOL_MS = 60L

    /** Long enough that billing it to the tool would be unmistakable. */
    private val APPROVAL_PAUSE_MS = 300L

    private val ChatUiState.assistantTexts: List<String>
        get() = entries.filterIsInstance<ChatEntry.FromAssistant>().map { it.text }

    // -- streaming -----------------------------------------------------------

    @Test
    fun `prose accumulates into one entry that keeps its id`() = runTest {
        // The id is read after **every** token, from inside the stream. The
        // finished list alone cannot tell an entry that kept its id from one
        // replaced per token by a new entry with the same text -- and the
        // latter is the bug: the LazyColumn re-keys the bubble on every token
        // and collapses the tool cards above it.
        lateinit var controller: ChatController
        val ids = mutableListOf<Long>()
        val client = object : AiClient {
            override val provider = AiProviderType.OPENAI
            override val model = "fake"
            override suspend fun send(request: AiClientRequest) = AiClientResponse(listOf(AiPart.Text("Hello there!")))
            override suspend fun send(
                request: AiClientRequest,
                onTextDelta: (String) -> Unit,
            ): AiClientResponse {
                listOf("Hello", " there", "!").forEach { delta ->
                    onTextDelta(delta)
                    ids += controller.state.value.entries.last().id
                }
                return AiClientResponse(listOf(AiPart.Text("Hello there!")))
            }

            override suspend fun complete(context: CompletionContext): String? = null
        }
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir)), Approver { _, _ -> true }, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("hi")
        advanceUntilIdle()

        assertEquals("the bubble changed id between tokens: $ids", 1, ids.distinct().size)
        val assistantEntries = controller.state.value.entries.filterIsInstance<ChatEntry.FromAssistant>()
        assertEquals(1, assistantEntries.size)
        assertEquals("Hello there!", assistantEntries.single().text)
        assertEquals("the finished bubble is not the one that streamed", ids.first(), assistantEntries.single().id)
        assertFalse("the finished bubble is still marked streaming", assistantEntries.single().streaming)
    }

    @Test
    fun `the bubble is marked streaming while tokens arrive`() = runTest(StandardTestDispatcher()) {
        // A client that reports what the state looked like mid-stream, which is
        // the only moment the caret and the hidden actions are decided by.
        var midStream: ChatUiState? = null
        lateinit var controller: ChatController
        val client = object : AiClient {
            override val provider = AiProviderType.OPENAI
            override val model = "fake"
            override suspend fun send(request: AiClientRequest) = AiClientResponse(listOf(AiPart.Text("done")))
            override suspend fun send(
                request: AiClientRequest,
                onTextDelta: (String) -> Unit,
            ): AiClientResponse {
                onTextDelta("par")
                midStream = controller.state.value
                onTextDelta("tial")
                return AiClientResponse(listOf(AiPart.Text("partial")))
            }

            override suspend fun complete(context: CompletionContext): String? = null
        }
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir)), Approver { _, _ -> true }, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("hi")
        advanceUntilIdle()

        val open = midStream!!.entries.last() as ChatEntry.FromAssistant
        assertTrue("mid-stream bubble was not marked streaming", open.streaming)
        assertEquals("par", open.text)
        assertEquals(open.id, midStream!!.streamingEntryId)
    }

    @Test
    fun `a tool card lands between the prose before it and the prose after`() = runTest {
        val controller = newController(
            Turn(
                deltas = listOf("Let me look. "),
                calls = listOf(AiPart.FunctionCall("1", "read_file", mapOf("path" to "Main.kt"))),
            ),
            Turn(listOf("It is empty.")),
            scope = this,
        )

        controller.send("what is in Main.kt?")
        advanceUntilIdle()

        val kinds = controller.state.value.entries.map {
            when (it) {
                is ChatEntry.FromUser -> "user"
                is ChatEntry.FromAssistant -> "assistant:${it.text}"
                is ChatEntry.Tool -> "tool:${it.name}"
            }
        }
        assertEquals(
            // "Let me look." without its trailing space: the authoritative text
            // arrives trimmed, and it replaces what streamed.
            listOf("user", "assistant:Let me look.", "tool:read_file", "assistant:It is empty."),
            kinds,
        )
    }

    @Test
    fun `a tool card is not shown twice`() = runTest {
        // The trap when tool runs became live: the reply still carries them, so
        // rendering `reply.toolRuns` at the end duplicates every card.
        val controller = newController(
            Turn(
                deltas = emptyList(),
                calls = listOf(AiPart.FunctionCall("1", "list_files", emptyMap())),
            ),
            Turn(listOf("one file.")),
            scope = this,
        )

        controller.send("what is here?")
        advanceUntilIdle()

        assertEquals(1, controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().size)
    }

    @Test
    fun `a tool card carries how long it took`() = runTest {
        // A tool that takes a known, real time. `durationMs >= 0` -- what this
        // asserted -- is true of a duration never measured at all, since the
        // field defaults to 0.
        val slow = AideTool(
            name = "slow_tool",
            description = "Takes a while.",
            risk = ToolRisk.READ_ONLY,
            parameters = emptyMap(),
            required = emptyList(),
        ) {
            Thread.sleep(SLOW_TOOL_MS)
            ProjectFiles.Outcome.Ok("done")
        }
        val client = FakeClient(
            listOf(
                Turn(emptyList(), listOf(AiPart.FunctionCall("1", "slow_tool", emptyMap()))),
                Turn(listOf("done")),
            ),
        )
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir), listOf(slow)), approver, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        val controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("look")
        advanceUntilIdle()

        val card = controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertTrue("a ${SLOW_TOOL_MS}ms tool was timed at ${card.durationMs}ms", card.durationMs >= SLOW_TOOL_MS)
    }

    /**
     * **The card must not charge the tool for the user's thinking time.**
     *
     * Driving the phone showed `ls -la` labelled `40.0s`, because the clock
     * started before the approval prompt and a person took that long to read
     * it. The number is an account of the tool, and a reader takes it to mean
     * the command was slow.
     */
    @Test
    fun `the duration excludes the wait for approval`() = runTest(StandardTestDispatcher()) {
        val calls = listOf(AiPart.FunctionCall("1", "edit_file", mapOf("path" to "Main.kt", "content" to "x")))
        val client = FakeClient(listOf(Turn(emptyList(), calls), Turn(listOf("done"))))
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir), extraTools), approver, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        val controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("edit it")
        advanceUntilIdle()
        // A deliberate wait, standing in for a person reading the prompt.
        Thread.sleep(APPROVAL_PAUSE_MS)
        controller.resolveApproval(true)
        advanceUntilIdle()

        val card = controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertTrue(
            "the approval wait was billed to the tool: ${card.durationMs}ms",
            card.durationMs < APPROVAL_PAUSE_MS,
        )
    }

    @Test
    fun `the final reply text replaces what was streamed`() = runTest {
        // The written-call case: the client streams the raw JSON as prose and
        // then returns it stripped. Trusting the deltas leaves JSON on screen.
        val client = object : AiClient {
            override val provider = AiProviderType.LOCAL
            override val model = "local"
            override suspend fun send(request: AiClientRequest) = AiClientResponse(listOf(AiPart.Text("clean")))
            override suspend fun send(
                request: AiClientRequest,
                onTextDelta: (String) -> Unit,
            ): AiClientResponse {
                onTextDelta("""{"name": "read_file"} """)
                return AiClientResponse(listOf(AiPart.Text("clean")))
            }

            override suspend fun complete(context: CompletionContext): String? = null
        }
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir)), Approver { _, _ -> true }, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        val controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("read it")
        advanceUntilIdle()

        assertEquals(listOf("clean"), controller.state.value.assistantTexts)
    }

    /**
     * **Found by driving the phone, and it was permanent, not a flash.**
     *
     * The 1.5B wrote its call as prose in a ```json fence. The client
     * recovered it and returned the text stripped, but the JSON had already
     * streamed, and the tool card that followed closed that bubble before the
     * end of the turn could correct it -- leaving a code block full of tool-call
     * markup above the answer for good.
     */
    @Test
    fun `a bubble whose prose was really a tool call is corrected before the card`() = runTest {
        val controller = newController(
            Turn(
                deltas = listOf("""{"name": "read_file", "arguments": {"path": "M.kt"}}"""),
                calls = listOf(AiPart.FunctionCall("", "read_file", mapOf("path" to "Main.kt"))),
                // What the client returns once it has taken the object out:
                // nothing was left but the call.
                settled = "",
            ),
            Turn(listOf("It prints nothing.")),
            scope = this,
        )

        controller.send("read Main.kt")
        advanceUntilIdle()

        val texts = controller.state.value.assistantTexts
        assertTrue("the raw tool call was left on screen: $texts", texts.none { it.contains("\"name\"") })
        assertEquals(listOf("It prints nothing."), texts)
        assertEquals(1, controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().size)
    }

    @Test
    fun `a settled text that only tidies the prose keeps the bubble`() = runTest {
        val controller = newController(
            Turn(
                deltas = listOf("Let me look. ", """{"name": "read_file"}"""),
                calls = listOf(AiPart.FunctionCall("", "read_file", mapOf("path" to "Main.kt"))),
                settled = "Let me look.",
            ),
            Turn(listOf("Done.")),
            scope = this,
        )

        controller.send("read it")
        advanceUntilIdle()

        assertEquals(listOf("Let me look.", "Done."), controller.state.value.assistantTexts)
    }

    @Test
    fun `a cancelled turn keeps the text that already arrived`() = runTest(StandardTestDispatcher()) {
        lateinit var controller: ChatController
        val client = object : AiClient {
            override val provider = AiProviderType.OPENAI
            override val model = "fake"
            override suspend fun send(request: AiClientRequest) = AiClientResponse(emptyList())
            override suspend fun send(
                request: AiClientRequest,
                onTextDelta: (String) -> Unit,
            ): AiClientResponse {
                onTextDelta("half an ans")
                controller.cancelSend()
                throw kotlinx.coroutines.CancellationException("stopped")
            }

            override suspend fun complete(context: CompletionContext): String? = null
        }
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir)), Approver { _, _ -> true }, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("go")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("half an ans"), state.assistantTexts)
        assertNull("a stopped turn left its caret blinking", state.streamingEntryId)
        assertFalse(state.sending)
    }

    @Test
    fun `a turn cancelled before any text leaves no empty bubble`() = runTest(StandardTestDispatcher()) {
        // Stopped from inside the request, once the turn is live and before a
        // token has arrived. Cancelling straight after send() -- what this did
        // -- stops a coroutine that has not started, so the turn never ran and
        // there was nothing that could have left a bubble behind.
        lateinit var controller: ChatController
        var reached = false
        val client = object : AiClient {
            override val provider = AiProviderType.OPENAI
            override val model = "fake"
            override suspend fun send(request: AiClientRequest) = AiClientResponse(emptyList())
            override suspend fun send(
                request: AiClientRequest,
                onTextDelta: (String) -> Unit,
            ): AiClientResponse {
                reached = true
                controller.cancelSend()
                throw kotlinx.coroutines.CancellationException("stopped")
            }

            override suspend fun complete(context: CompletionContext): String? = null
        }
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir)), Approver { _, _ -> true }, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("go")
        advanceUntilIdle()

        assertTrue("the turn never reached the provider", reached)
        assertEquals(emptyList<String>(), controller.state.value.assistantTexts)
        assertNull(controller.state.value.streamingEntryId)
        assertFalse(controller.state.value.sending)
    }

    // -- redoing a turn ------------------------------------------------------

    @Test
    fun `regenerate drops the old answer and asks again`() = runTest {
        val controller = newController(Turn(listOf("first")), Turn(listOf("second")), scope = this)
        controller.send("question")
        advanceUntilIdle()

        controller.regenerate()
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("second"), state.assistantTexts)
        assertEquals(1, state.entries.filterIsInstance<ChatEntry.FromUser>().size)
    }

    @Test
    fun `editing a message replaces it and drops what followed`() = runTest {
        val controller = newController(Turn(listOf("about A")), Turn(listOf("about B")), scope = this)
        controller.send("tell me about A")
        advanceUntilIdle()
        val userEntry = controller.state.value.entries.filterIsInstance<ChatEntry.FromUser>().single()

        controller.editAndResend(userEntry.id, "tell me about B")
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(listOf("tell me about B"), state.entries.filterIsInstance<ChatEntry.FromUser>().map { it.text })
        assertEquals(listOf("about B"), state.assistantTexts)
    }

    @Test
    fun `regenerate does nothing before the first message`() = runTest {
        val controller = newController(Turn(listOf("x")), scope = this)

        controller.regenerate()
        advanceUntilIdle()

        assertTrue(controller.state.value.entries.isEmpty())
    }

    // -- approval scope ------------------------------------------------------

    @Test
    fun `a conversation-wide approval is not asked for twice`() = runTest(StandardTestDispatcher()) {
        var prompts = 0
        lateinit var controller: ChatController
        // Two different edits, for the reason the next test gives: an identical
        // repeat is answered by the session's dedup guard and never reaches the
        // approver, so this used to pass with the grant ignored entirely.
        val client = FakeClient(
            listOf(
                Turn(emptyList(), listOf(AiPart.FunctionCall("1", "edit_file", mapOf("path" to "A.kt", "content" to "x")))),
                Turn(emptyList(), listOf(AiPart.FunctionCall("2", "edit_file", mapOf("path" to "B.kt", "content" to "y")))),
                Turn(listOf("done")),
            ),
        )
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(
                    client,
                    ProjectToolset(ProjectFiles(projectDir), extraTools),
                    approver,
                    unconfinedDispatchers,
                )

            override fun completer(): InlineCompleter? = null
        }
        controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("edit it")
        // Answer each prompt as it appears, granting for the conversation the
        // first time. A second prompt would mean the grant was not honoured.
        repeat(6) {
            advanceUntilIdle()
            if (controller.state.value.pendingApproval != null) {
                prompts++
                controller.resolveApproval(true, ApprovalScope.CONVERSATION)
            }
        }
        advanceUntilIdle()

        assertEquals("asked more than once for a standing approval", 1, prompts)
        assertEquals(setOf("edit_file"), controller.state.value.standingApprovals)
        // The second edit ran: approved by the grant, not stopped by the guard.
        assertEquals("y", File(projectDir, "B.kt").readText())
    }

    @Test
    fun `a once-only approval is asked again`() = runTest(StandardTestDispatcher()) {
        var prompts = 0
        // **Two different edits, deliberately.** An identical repeat never
        // reaches the approver at all: the session's dedup guard answers it
        // from the previous result, which is the §9 fix doing its job. Writing
        // this with the same arguments twice asserted that guard, not this one,
        // and passed for the wrong reason.
        val client = FakeClient(
            listOf(
                Turn(emptyList(), listOf(AiPart.FunctionCall("1", "edit_file", mapOf("path" to "A.kt", "content" to "x")))),
                Turn(emptyList(), listOf(AiPart.FunctionCall("2", "edit_file", mapOf("path" to "B.kt", "content" to "y")))),
                Turn(listOf("done")),
            ),
        )
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir), extraTools), approver, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        val controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("edit it")
        repeat(8) {
            advanceUntilIdle()
            if (controller.state.value.pendingApproval != null) {
                prompts++
                controller.resolveApproval(true, ApprovalScope.ONCE)
            }
        }
        advanceUntilIdle()

        assertEquals(2, prompts)
        assertTrue(controller.state.value.standingApprovals.isEmpty())
    }

    @Test
    fun `waiting for approval says so instead of claiming to think`() = runTest(StandardTestDispatcher()) {
        val calls = listOf(AiPart.FunctionCall("1", "edit_file", mapOf("path" to "Main.kt", "content" to "x")))
        val client = FakeClient(listOf(Turn(emptyList(), calls), Turn(listOf("done"))))
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>) =
                AiSession(client, ProjectToolset(ProjectFiles(projectDir), extraTools), approver, unconfinedDispatchers)

            override fun completer(): InlineCompleter? = null
        }
        val controller = ChatController(assistant, projectDir, this, io = Dispatchers.Unconfined)

        controller.send("edit it")
        advanceUntilIdle()
        val waitingStatus = controller.state.value.activeStatus
        // Answered before the assertion, so the turn can finish: an unresolved
        // prompt leaves a live coroutine and `runTest` fails on that instead of
        // on the thing under test.
        controller.resolveApproval(true)
        advanceUntilIdle()

        assertEquals("Waiting for you to approve edit_file", waitingStatus)
    }

    // -- history -------------------------------------------------------------

    @Test
    fun `a conversation is saved and can be reopened`() = runTest {
        val store = ConversationStore(storeRoot, projectDir)
        val controller = newController(Turn(listOf("saved answer")), store = store, scope = this)

        controller.send("a question worth keeping")
        advanceUntilIdle()
        val id = controller.state.value.activeConversationId!!
        controller.newChat()
        assertTrue(controller.state.value.entries.isEmpty())

        controller.openConversation(id)

        val state = controller.state.value
        assertEquals(listOf("a question worth keeping"), state.entries.filterIsInstance<ChatEntry.FromUser>().map { it.text })
        assertEquals(listOf("saved answer"), state.assistantTexts)
        assertEquals("a question worth keeping", state.activeTitle)
    }

    @Test
    fun `a new chat does not destroy the one being left`() = runTest(StandardTestDispatcher()) {
        // Left **mid-turn**: the question is on screen and nothing has saved it
        // yet. A finished turn -- what this used to wait for -- saves itself, so
        // the test passed with newChat's own save deleted.
        val store = ConversationStore(storeRoot, projectDir)
        val controller = newController(Turn(listOf("answer")), store = store, scope = this)
        controller.send("first chat")

        controller.newChat()
        advanceUntilIdle()

        val saved = store.list().single()
        assertTrue(
            "the question being asked was lost",
            store.load(saved.id).any { it is ChatEntry.FromUser && it.text == "first chat" },
        )
        assertEquals(1, controller.state.value.conversations.size)
    }

    @Test
    fun `an empty chat is never stored`() = runTest {
        val store = ConversationStore(storeRoot, projectDir)
        val controller = newController(Turn(listOf("x")), store = store, scope = this)

        controller.newChat()
        controller.newChat()

        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `deleting the open conversation clears the panel`() = runTest {
        val store = ConversationStore(storeRoot, projectDir)
        val controller = newController(Turn(listOf("answer")), store = store, scope = this)
        controller.send("doomed")
        advanceUntilIdle()
        val id = controller.state.value.activeConversationId!!

        controller.deleteConversation(id)

        assertTrue(controller.state.value.entries.isEmpty())
        assertNull(controller.state.value.activeConversationId)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `renaming keeps the name when the chat is reopened`() = runTest {
        val store = ConversationStore(storeRoot, projectDir)
        val controller = newController(Turn(listOf("answer")), store = store, scope = this)
        controller.send("original question")
        advanceUntilIdle()
        val id = controller.state.value.activeConversationId!!

        controller.renameConversation(id, "Build failures")
        controller.newChat()
        controller.openConversation(id)

        assertEquals("Build failures", controller.state.value.activeTitle)
    }

    @Test
    fun `regenerate is offered only once there is something to redo`() = runTest {
        val controller = newController(Turn(listOf("answer")), scope = this)
        assertFalse(controller.state.value.canRegenerate)

        controller.send("question")
        advanceUntilIdle()

        assertTrue(controller.state.value.canRegenerate)
    }
}
