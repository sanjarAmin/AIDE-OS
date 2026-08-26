package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The chat panel's state machine.
 *
 * The approval handshake is what this file is really for. A mutating tool has
 * to park the whole loop on a decision made in the UI, and the two ways that
 * breaks are both silent: the loop carries on without waiting (the file is
 * written before the user answers), or it never resumes (the panel sits on
 * "sending" forever). Both are asserted here by stepping the clock explicitly
 * rather than by waiting.
 */
@RunWith(AndroidJUnit4::class)
class ChatControllerTest {

    private lateinit var root: File
    private lateinit var keys: ApiKeyStore
    private var api: ScriptedApi? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        keys = ApiKeyStore(context)
        keys.clear()
        root = File(context.cacheDir, "chat-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }.writeText("fun main() = Unit")
    }

    @After
    fun tearDown() {
        api?.stop()
        keys.clear()
        root.deleteRecursively()
    }

    /**
     * A controller whose IO runs on the test scheduler.
     *
     * Not a detail: the session's request goes through `withContext(io)`, and
     * with a real `Dispatchers.IO` the HTTP call lands on a thread the test
     * clock knows nothing about. `advanceUntilIdle()` then returns while the
     * turn is still in flight and every assertion reads a half-finished state.
     * Pinning IO to the scheduler makes the blocking call complete inside
     * `advanceUntilIdle()`, which is what the tests below assume.
     */
    private fun TestScope.controller(responses: List<String>): ChatController {
        val onScheduler = StandardTestDispatcher(testScheduler)
        val assistant = Assistant(
            keys = keys,
            dispatchers = object : DispatcherProvider {
                override val main: CoroutineDispatcher get() = onScheduler
                override val default: CoroutineDispatcher get() = onScheduler
                override val io: CoroutineDispatcher get() = onScheduler
                override val compiler: CoroutineDispatcher get() = onScheduler
            },
            clientFactory = { ScriptedApi(responses).also { api = it }.client() },
        )
        return ChatController(assistant, root, this)
    }

    @Test
    fun a_missing_key_is_reported_rather_than_thrown() = runTest {
        val controller = controller(listOf(ScriptedApi.text("never asked")))

        controller.send("hello")
        advanceUntilIdle()

        assertTrue("the panel should be asking for a key", controller.state.value.needsKey)
        assertFalse(controller.state.value.sending)
        assertEquals(listOf("hello"), controller.userTexts())
    }

    @Test
    fun an_answer_lands_in_the_transcript() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(listOf(ScriptedApi.text("It does nothing.")))

        controller.send("what does Main.kt do?")
        advanceUntilIdle()

        assertEquals(
            listOf("what does Main.kt do?", "It does nothing."),
            controller.state.value.entries.map {
                when (it) {
                    is ChatEntry.FromUser -> it.text
                    is ChatEntry.FromAssistant -> it.text
                    is ChatEntry.Tool -> "tool:${it.name}"
                }
            },
        )
        assertFalse(controller.state.value.sending)
    }

    /**
     * The half of the handshake that protects the project.
     *
     * Between the model asking to write and the user answering, the loop must
     * be genuinely parked -- the file untouched and the second request unsent.
     * A controller that emitted the prompt and carried on would still show a
     * dialog, and would already have overwritten the file behind it.
     */
    @Test
    fun a_mutating_tool_parks_the_loop_until_the_user_answers() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call(
                        "tu_1",
                        "edit_file",
                        """{"path":"src/Main.kt","content":"fun main() = println(42)"}""",
                    ),
                ),
                ScriptedApi.text("Done."),
            ),
        )

        controller.send("rewrite Main.kt")
        advanceUntilIdle()

        val pending = controller.state.value.pendingApproval
        assertNotNull("no approval was requested for a mutating tool", pending)
        assertEquals("edit_file", pending!!.toolName)
        assertEquals("src/Main.kt", pending.path)
        assertTrue("the prompt should show what would be written", "println(42)" in pending.preview)

        assertEquals(
            "the file was written before the user answered",
            "fun main() = Unit",
            File(root, "src/Main.kt").readText(),
        )
        assertEquals("the loop did not wait for the answer", 1, api!!.requestCount)
        assertTrue("the panel should still read as busy", controller.state.value.sending)

        // Released only now that the assertions have run. `runTest` waits for
        // every child coroutine before it returns, and a genuinely parked loop
        // is a child that never finishes -- leaving it parked ends the test in
        // a 60-second timeout rather than a pass.
        controller.resolveApproval(false)
        advanceUntilIdle()
    }

    /** And the other half: answering really does resume it. */
    @Test
    fun approving_resumes_the_loop_and_the_edit_lands() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call(
                        "tu_1",
                        "edit_file",
                        """{"path":"src/Main.kt","content":"fun main() = println(42)"}""",
                    ),
                ),
                ScriptedApi.text("Done."),
            ),
        )

        controller.send("rewrite Main.kt")
        advanceUntilIdle()
        controller.resolveApproval(true)
        advanceUntilIdle()

        assertEquals("fun main() = println(42)", File(root, "src/Main.kt").readText())
        assertNull(controller.state.value.pendingApproval)
        assertFalse("the panel is stuck sending", controller.state.value.sending)
        assertEquals("Done.", controller.assistantTexts().last())
    }

    @Test
    fun declining_leaves_the_file_alone_and_still_finishes_the_turn() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call(
                        "tu_1",
                        "edit_file",
                        """{"path":"src/Main.kt","content":"gone"}""",
                    ),
                ),
                ScriptedApi.text("Left it as it was."),
            ),
        )

        controller.send("rewrite Main.kt")
        advanceUntilIdle()
        controller.resolveApproval(false)
        advanceUntilIdle()

        assertEquals("fun main() = Unit", File(root, "src/Main.kt").readText())
        assertFalse(controller.state.value.sending)

        val tool = controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertTrue("a declined edit should be marked as declined", tool.declined)
        assertFalse("a decline is not a failure", tool.failed)
    }

    /** Read-only tools never park, so the turn completes in one go. */
    @Test
    fun a_read_only_tool_runs_without_a_prompt() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "read_file", """{"path":"src/Main.kt"}"""),
                ),
                ScriptedApi.text("Read it."),
            ),
        )

        controller.send("read Main.kt")
        advanceUntilIdle()

        assertNull(controller.state.value.pendingApproval)
        val tool = controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertEquals("read_file", tool.name)
        assertFalse(tool.declined)
        assertFalse(tool.failed)
    }

    /**
     * A tool that fails for a reason the user did not cause is shown as failed.
     *
     * Rendering it like a successful read is how the user comes to believe the
     * assistant saw a file it never opened.
     */
    @Test
    fun a_tool_that_refuses_on_its_own_is_marked_failed() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(
            listOf(
                ScriptedApi.toolUse(
                    ScriptedApi.Companion.Call("tu_1", "read_file", """{"path":"../secrets"}"""),
                ),
                ScriptedApi.text("I could not read that."),
            ),
        )

        controller.send("read the secrets")
        advanceUntilIdle()

        val tool = controller.state.value.entries.filterIsInstance<ChatEntry.Tool>().single()
        assertTrue("an escape attempt should show as a failed call", tool.failed)
        assertFalse(tool.declined)
    }

    /** An empty message is not a turn, and must not consume one. */
    @Test
    fun blank_input_is_ignored() = runTest {
        keys.save("sk-ant-test")
        val controller = controller(listOf(ScriptedApi.text("should not be asked")))

        controller.send("   ")
        advanceUntilIdle()

        assertTrue(controller.state.value.entries.isEmpty())
        assertNull(api)
    }

    private fun ChatController.userTexts() =
        state.value.entries.filterIsInstance<ChatEntry.FromUser>().map { it.text }

    private fun ChatController.assistantTexts() =
        state.value.entries.filterIsInstance<ChatEntry.FromAssistant>().map { it.text }
}
