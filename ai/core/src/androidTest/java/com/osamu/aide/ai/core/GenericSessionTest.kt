package com.osamu.aide.ai.core

import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The *other* tool loop — `AiSession.sendGeneric`, for Gemini, OpenAI and
 * Custom.
 *
 * `AiSessionTest` covers `sendAnthropic`. The two loops share nothing but
 * `executeTool`, so every rule that milestone paid for had to be re-established
 * here from scratch; the same green suite covered none of it. These are that
 * milestone's cases ported, and the ported form is not a copy — the rules are
 * the same, the wire shapes that satisfy them are not:
 *
 * - **The assistant's turn is replayed whole.** Anthropic needs its thinking
 *   blocks back verbatim. These providers have no equivalent, but the reason
 *   survives: a tool result whose call is not in the history is an orphan, and
 *   OpenAI rejects it outright.
 * - **Results go back in the shape the protocol wants.** For Anthropic that is
 *   one user message holding every result; for Gemini one user turn of
 *   `functionResponse` parts; for OpenAI one `role: "tool"` message *per* call.
 *   Porting the Anthropic assertion literally would have asserted a bug.
 * - **Every call gets a result, refusals included.**
 *
 * Subclasses supply the provider. Everything here goes through the real client
 * against a local server, for the reason [ScriptedProviderApi] documents.
 */
abstract class GenericSessionTest {

    protected lateinit var root: File
    protected lateinit var toolset: ProjectToolset
    protected var api: ScriptedProviderApi? = null

    /** Everything on the caller's thread: `runTest` then controls the clock. */
    protected val unconfined = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val compiler: CoroutineDispatcher get() = Dispatchers.Unconfined
    }

    // -- what a provider has to supply ---------------------------------------

    protected abstract fun client(api: ScriptedProviderApi): AiClient

    /** A finished turn: prose, no calls. */
    protected abstract fun text(text: String): String

    /** A turn asking for tools. */
    protected abstract fun toolCall(vararg calls: ScriptedProviderApi.Call): String

    /**
     * The request carrying tool results still contains the turn that asked for
     * them.
     */
    protected abstract fun assertAssistantTurnReplayed(body: String)

    /** Both results came back, in whatever shape this protocol requires. */
    protected abstract fun assertBothResultsReturned(body: String, first: String, second: String)

    // -- fixture -------------------------------------------------------------

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "generic-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }
            .writeText("fun main() = println(\"hi\")")
        toolset = ProjectToolset(ProjectFiles(root))
    }

    @After
    fun tearDown() {
        api?.stop()
        root.deleteRecursively()
    }

    protected fun session(
        responses: List<String>,
        approver: Approver = Approver { _, _ -> true },
        maxToolRounds: Int = 12,
    ): AiSession {
        val scripted = ScriptedProviderApi(responses).also { api = it }
        return AiSession(
            aiClient = client(scripted),
            toolset = toolset,
            approver = approver,
            dispatchers = unconfined,
            maxToolRounds = maxToolRounds,
        )
    }

    private fun read(path: String, id: String = "call_1") =
        ScriptedProviderApi.Call(id, "read_file", mapOf("path" to path))

    // -- the cases -----------------------------------------------------------

    @Test
    fun a_plain_answer_needs_one_request() = runTest {
        val reply = session(listOf(text("Nothing to do."))).send("ctx", "hello")

        assertEquals("Nothing to do.", reply.text)
        assertTrue(reply.toolRuns.isEmpty())
        assertFalse(reply.truncated)
        assertEquals(1, api!!.requestCount)
    }

    @Test
    fun a_tool_call_is_run_and_its_output_returned_to_the_model() = runTest {
        val reply = session(
            listOf(
                toolCall(read("src/Main.kt")),
                text("It prints hi."),
            ),
        ).send("ctx", "what does Main.kt do?")

        assertEquals("It prints hi.", reply.text)
        assertEquals(listOf("read_file"), reply.toolRuns.map { it.name })
        assertEquals(2, api!!.requestCount)

        val second = api!!.body(1)
        assertTrue("the file's contents never reached the model:\n$second", "println" in second)
    }

    /**
     * The analogue of the Anthropic loop's verbatim thinking replay.
     *
     * There are no thinking blocks to preserve here, but the failure they
     * guarded against is the same one: a history that carries the tool *result*
     * without the turn that asked for it. OpenAI answers that with a 400 naming
     * an orphaned `tool` message; Gemini simply loses the thread. Neither shows
     * up in the reply text of the first turn, which is why this is a test and
     * not a comment.
     */
    @Test
    fun the_assistant_turn_is_replayed_so_the_result_is_not_an_orphan() = runTest {
        session(
            listOf(
                toolCall(ScriptedProviderApi.Call("call_1", "list_files", emptyMap())),
                text("done"),
            ),
        ).send("ctx", "what is in here?")

        assertAssistantTurnReplayed(api!!.body(1))
    }

    /**
     * Parallel calls, and the shape the results go back in.
     *
     * The Anthropic rule — one user message holding every result — is a fact
     * about that API, not about tool loops. Each subclass asserts its own
     * protocol's shape; what is shared is that both calls ran and neither
     * result was dropped.
     */
    @Test
    fun parallel_calls_both_run_and_neither_result_is_dropped() = runTest {
        val reply = session(
            listOf(
                toolCall(
                    read("src/Main.kt", id = "call_1"),
                    ScriptedProviderApi.Call("call_2", "grep", mapOf("query" to "fun")),
                ),
                text("both read"),
            ),
        ).send("ctx", "read and search")

        assertEquals(listOf("read_file", "grep"), reply.toolRuns.map { it.name })
        assertEquals(2, api!!.requestCount)

        assertBothResultsReturned(api!!.body(1), "call_1", "call_2")
    }

    /** A refusal is still a result, or the model is left with a hole. */
    @Test
    fun a_declined_edit_is_reported_to_the_model_rather_than_dropped() = runTest {
        val reply = session(
            responses = listOf(
                toolCall(
                    ScriptedProviderApi.Call(
                        "call_1",
                        "edit_file",
                        mapOf("path" to "src/Main.kt", "content" to "fun main() = Unit"),
                    ),
                ),
                text("Understood, leaving it alone."),
            ),
            approver = Approver { _, _ -> false },
        ).send("ctx", "empty out Main.kt")

        assertFalse("the edit should not have been approved", reply.toolRuns.single().approved)
        assertEquals(
            "the file was written despite the refusal",
            "fun main() = println(\"hi\")",
            File(root, "src/Main.kt").readText(),
        )

        // Named rather than identified: Gemini has no call ids and matches a
        // result to its call by function name, so an id assertion here would be
        // the Anthropic protocol leaking into a shared case. OpenAI's id round
        // trip has its own test, where it is a real requirement.
        val second = api!!.body(1)
        assertTrue("no result was sent for the declined call:\n$second", "edit_file" in second)
        assertTrue(
            "the model was not told why the call produced nothing:\n$second",
            "not confirmed by the user" in second,
        )
    }

    /** And approval really does let the write through. */
    @Test
    fun an_approved_edit_reaches_the_project() = runTest {
        session(
            listOf(
                toolCall(
                    ScriptedProviderApi.Call(
                        "call_1",
                        "edit_file",
                        mapOf("path" to "src/New.kt", "content" to "val answer = 42"),
                    ),
                ),
                text("Written."),
            ),
        ).send("ctx", "add New.kt")

        assertEquals("val answer = 42", File(root, "src/New.kt").readText())
    }

    /**
     * The approver is asked only about tools that can change something.
     *
     * A prompt on every read trains the user to tap "allow" without looking,
     * which is how the one prompt that mattered gets approved.
     */
    @Test
    fun read_only_tools_do_not_prompt() = runTest {
        val asked = mutableListOf<String>()

        session(
            responses = listOf(
                toolCall(ScriptedProviderApi.Call("call_1", "list_files", emptyMap())),
                text("done"),
            ),
            approver = Approver { name, _ -> asked += name; true },
        ).send("ctx", "look around")

        assertTrue("a read-only tool asked for confirmation: $asked", asked.isEmpty())
    }

    /**
     * The runaway guard, which matters more here than on the Anthropic path:
     * these providers are billed per call too, and a loop that never terminates
     * looks like progress from the outside for as long as it runs.
     */
    @Test
    fun a_model_that_never_stops_calling_tools_is_cut_off() = runTest {
        val reply = session(
            responses = listOf(
                toolCall(ScriptedProviderApi.Call("call_1", "list_files", emptyMap())),
            ),
            maxToolRounds = 3,
        ).send("ctx", "go")

        assertTrue("the loop should have reported being cut short", reply.truncated)
        assertEquals(3, api!!.requestCount)
        assertEquals(3, reply.toolRuns.size)
    }

    /** History survives across turns, which is what makes it a conversation. */
    @Test
    fun the_conversation_accumulates_across_turns() = runTest {
        val session = session(listOf(text("first"), text("second")))

        session.send("ctx", "one")
        session.send("ctx", "two")

        assertEquals(4, session.history.size)
        val second = api!!.body(1)
        assertTrue("the first turn was not carried forward:\n$second", "one" in second)
    }

    /**
     * The project context reaches the model.
     *
     * `sendGeneric` builds its own system instruction rather than going through
     * `PromptAssembler`, so nothing the Anthropic path does guarantees this.
     * Dropping it costs no error and no visible failure — the assistant simply
     * answers about a project it cannot see.
     */
    @Test
    fun the_project_context_reaches_the_model() = runTest {
        session(listOf(text("ok"))).send("PROJECT_CONTEXT_MARKER", "hello")

        val first = api!!.body(0)
        assertTrue("the project context was not sent:\n$first", "PROJECT_CONTEXT_MARKER" in first)
    }

    /**
     * The tools are declared.
     *
     * The purest example of this milestone's failure mode: a request without
     * tool declarations gets a perfectly good answer back. The model just never
     * asks to read a file, and the assistant quietly degrades into a chatbot
     * that cannot see the project. The scripted turn here is plain prose, so
     * only a declaration can put these names in the body.
     */
    @Test
    fun the_tools_are_declared_or_the_model_can_never_call_one() = runTest {
        session(listOf(text("ok"))).send("ctx", "hello")

        val first = api!!.body(0)
        for (tool in listOf("list_files", "read_file", "grep", "edit_file")) {
            assertTrue("$tool was not declared to the model:\n$first", tool in first)
        }
    }
}
