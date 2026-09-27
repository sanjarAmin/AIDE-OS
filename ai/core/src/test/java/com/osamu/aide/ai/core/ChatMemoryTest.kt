package com.osamu.aide.ai.core

import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * What the model is sent, as opposed to what the panel shows.
 *
 * **The two had drifted apart without anything noticing.** Every path that
 * rebuilt the session -- regenerate, edit, reopening a chat, Stop -- showed the
 * whole conversation on screen while the model received only the next message.
 * Every assertion in the other chat tests is about the transcript, so all of
 * them passed. These read the requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMemoryTest {

    private lateinit var projectDir: File
    private lateinit var storeRoot: File

    private val unconfined = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        projectDir = tempDir("chat-memory-project")
        storeRoot = tempDir("chat-memory-store")
    }

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").also { it.delete(); it.mkdirs() }

    /**
     * Answers each request with "A<n>", streaming it as one delta, and keeps a
     * copy of every conversation it was sent -- copied on arrival, because the
     * session hands over its live list and appends to it afterwards.
     */
    private class RecordingClient : AiClient {
        override val provider = AiProviderType.OPENAI
        override val model = "fake"
        val sent = mutableListOf<List<String>>()

        /** Runs before each delta; a test uses it to press Stop mid-stream. */
        var beforeDelta: (index: Int) -> Unit = {}
        var deltasPerReply = 1
        var deltasDelivered = 0

        override suspend fun send(request: AiClientRequest): AiClientResponse = send(request) {}

        override suspend fun send(
            request: AiClientRequest,
            onTextDelta: (String) -> Unit,
        ): AiClientResponse {
            sent += request.messages.map { message ->
                val who = if (message.role == AiRole.USER) "U" else "A"
                who + ":" + message.parts.filterIsInstance<AiPart.Text>().joinToString("") { it.text }
            }
            val answer = "A${sent.size}"
            for (i in 0 until deltasPerReply) {
                beforeDelta(i)
                onTextDelta(if (deltasPerReply == 1) answer else "$answer.$i ")
                deltasDelivered++
            }
            return AiClientResponse(listOf(AiPart.Text(answer)))
        }

        override suspend fun complete(context: CompletionContext): String? = null
    }

    private var sessionsBuilt = 0

    private fun controller(
        client: RecordingClient,
        scope: CoroutineScope,
        store: ConversationStore? = null,
    ): ChatController {
        val assistant = object : Assistant() {
            override fun session(projectDir: File, approver: Approver, extraTools: List<AideTool>): AiSession {
                sessionsBuilt++
                return AiSession(
                    aiClient = client,
                    toolset = ProjectToolset(ProjectFiles(projectDir), extraTools),
                    approver = approver,
                    dispatchers = unconfined,
                )
            }

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

    @Test
    fun `a regenerated answer is written knowing the turns before it`() = runTest {
        val client = RecordingClient()
        val chat = controller(client, this)
        chat.send("Q1"); advanceUntilIdle()
        chat.send("Q2"); advanceUntilIdle()

        chat.regenerate(); advanceUntilIdle()

        // The rejected answer to Q2 is gone; everything before it is not.
        assertEquals(listOf("U:Q1", "A:A1", "U:Q2"), client.sent.last())
    }

    @Test
    fun `an edited message is answered knowing the turns before it`() = runTest {
        val client = RecordingClient()
        val chat = controller(client, this)
        chat.send("Q1"); advanceUntilIdle()
        chat.send("Q2"); advanceUntilIdle()
        val second = chat.state.value.entries.filterIsInstance<ChatEntry.FromUser>()[1]

        chat.editAndResend(second.id, "Q2, better"); advanceUntilIdle()

        assertEquals(listOf("U:Q1", "A:A1", "U:Q2, better"), client.sent.last())
    }

    @Test
    fun `a reopened conversation is remembered by the model`() = runTest {
        val client = RecordingClient()
        val store = ConversationStore(storeRoot, projectDir)
        val chat = controller(client, this, store)
        chat.send("Q1"); advanceUntilIdle()
        val id = chat.state.value.activeConversationId!!

        chat.newChat()
        chat.openConversation(id)
        chat.send("Q2"); advanceUntilIdle()

        assertEquals(listOf("U:Q1", "A:A1", "U:Q2"), client.sent.last())
    }

    @Test
    fun `Stop ends the stream and the next question does not share its session`() = runTest {
        val client = RecordingClient().apply { deltasPerReply = 3 }
        val chat = controller(client, this)
        // Stop is pressed while the second delta is on its way -- from the
        // reader's own thread, which is where a blocking stream is.
        client.beforeDelta = { index -> if (index == 1) chat.cancelSend() }

        chat.send("Q1"); advanceUntilIdle()

        // The delta after Stop is refused, which is what ends the read loop
        // and drops the connection. Before, all three arrived.
        assertEquals(1, client.deltasDelivered)
        val shown = chat.state.value.entries.filterIsInstance<ChatEntry.FromAssistant>()
        assertEquals(listOf("A1.0 "), shown.map { it.text })
        assertFalse(chat.state.value.sending)

        client.beforeDelta = {}
        client.deltasPerReply = 1
        chat.send("Q2"); advanceUntilIdle()

        // A fresh session, told what the stopped answer had already said.
        assertEquals(2, sessionsBuilt)
        assertEquals(listOf("U:Q1", "A:A1.0 ", "U:Q2"), client.sent.last())
        assertFalse(chat.state.value.sending)
    }

    @Test
    fun `an ordinary follow-up reuses the session and its full history`() = runTest {
        val client = RecordingClient()
        val chat = controller(client, this)
        chat.send("Q1"); advanceUntilIdle()
        chat.send("Q2"); advanceUntilIdle()

        assertEquals(1, sessionsBuilt)
        assertEquals(listOf("U:Q1", "A:A1", "U:Q2"), client.sent.last())
    }

    // -- the transcript as turns --------------------------------------------

    private fun user(text: String) = ChatEntry.FromUser(text)
    private fun answer(text: String) = ChatEntry.FromAssistant(text)
    private fun tool() = ChatEntry.Tool(name = "read_file", detail = "Main.kt", declined = false, failed = false)

    private fun turns(vararg entries: ChatEntry) =
        ChatEntry.priorTurns(entries.toList()).map { (if (it.fromUser) "U:" else "A:") + it.text }

    @Test
    fun `one answer split by tool cards is one turn, and the cards carry no words`() {
        assertEquals(
            listOf("U:fix it", "A:Looking.\n\nFixed."),
            turns(user("fix it"), answer("Looking."), tool(), answer("Fixed.")),
        )
    }

    @Test
    fun `a stopped question with no answer still alternates`() {
        assertEquals(
            listOf("U:first", "A:" + ChatEntry.NO_ANSWER, "U:second", "A:ok"),
            turns(user("first"), user("second"), answer("ok")),
        )
        assertEquals(
            listOf("U:only", "A:" + ChatEntry.NO_ANSWER),
            turns(user("only")),
        )
    }

    @Test
    fun `turns start with the user and skip blank text`() {
        assertEquals(
            listOf("U:q", "A:a"),
            turns(answer("orphan"), user("q"), answer(" "), answer("a")),
        )
        assertTrue(turns().isEmpty())
    }
}
