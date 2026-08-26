package com.osamu.aide.ai.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anthropic.models.messages.OutputConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The request layout, which is where M5's cost model lives.
 *
 * Prompt caching is prefix-match: `tools` -> `system` -> `messages`, and any
 * byte that changes invalidates everything after it. The failure mode is what
 * makes this worth testing — a broken cache returns a **perfectly correct
 * answer** and simply costs more, so nothing surfaces until a bill arrives.
 * There is no exception to catch and no wrong output to notice.
 *
 * So these tests assert prefix *stability* rather than behaviour: given the
 * same project, the cacheable part of two consecutive requests must be
 * byte-identical, whatever the user said in between.
 */
@RunWith(AndroidJUnit4::class)
class PromptAssemblerTest {

    private lateinit var assembler: PromptAssembler

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "assembler-${System.nanoTime()}").apply { mkdirs() }
        File(root, "src/Main.kt").apply { parentFile?.mkdirs() }.writeText("fun main() = Unit")
        assembler = PromptAssembler(ProjectToolset(ProjectFiles(root)))
    }

    /** The text of every system block, in order. */
    private fun systemText(params: com.anthropic.models.messages.MessageCreateParams): List<String> =
        params.system().orElseThrow().textBlockParams().orElseThrow().map { it.text() }

    @Test
    fun the_system_prompt_is_split_with_the_breakpoint_on_the_context() {
        val params = assembler.request("file tree here", listOf(userTurn("hi")))
        val blocks = params.system().orElseThrow().textBlockParams().orElseThrow()

        assertEquals("expected instructions and context as separate blocks", 2, blocks.size)
        assertFalse(
            "the standing instructions must not carry the breakpoint",
            blocks[0].cacheControl().isPresent,
        )
        assertTrue(
            "the project context should carry the cache breakpoint",
            blocks[1].cacheControl().isPresent,
        )
    }

    /**
     * The property the whole layout exists for.
     *
     * Two turns of the same conversation must share a byte-identical cacheable
     * prefix. If the user's words leak into the system blocks — a "you asked
     * about X" line, a timestamp, the focused filename — this fails, and
     * without it the failure would only ever show up as cost.
     */
    @Test
    fun the_cacheable_prefix_does_not_change_when_the_user_speaks() {
        val context = "src/Main.kt\nsrc/Other.kt"

        val first = assembler.request(context, listOf(userTurn("what does Main do?")))
        val second = assembler.request(
            context,
            listOf(
                userTurn("what does Main do?"),
                assistantTurn("Nothing yet."),
                userTurn("add a greeting, and also what time is it"),
            ),
        )

        assertEquals("the system prefix changed between turns", systemText(first), systemText(second))
        assertEquals(
            "the tool definitions changed between turns",
            first.tools().orElseThrow().map { it.tool().orElseThrow().name() },
            second.tools().orElseThrow().map { it.tool().orElseThrow().name() },
        )
    }

    /** And the conversation really is carried, just after the breakpoint. */
    @Test
    fun the_conversation_lands_in_messages_not_in_the_prefix() {
        val params = assembler.request(
            "context",
            listOf(
                userTurn("first question"),
                assistantTurn("an answer"),
                userTurn("second question"),
            ),
        )

        assertEquals(3, params.messages().size)
        systemText(params).forEach { block ->
            assertFalse("a user turn leaked into the system prefix:\n$block", "second question" in block)
        }
    }

    /**
     * Changing the project *should* invalidate the cache — that is correct, not
     * a bug. Pinned so nobody "fixes" the context out of the cached region to
     * chase a higher hit rate and leaves the model blind to the project.
     */
    @Test
    fun a_changed_project_changes_the_prefix() {
        val before = assembler.request("src/Main.kt", emptyList())
        val after = assembler.request("src/Main.kt\nsrc/New.kt", emptyList())

        assertFalse(
            "the project context is not reaching the prompt at all",
            systemText(before) == systemText(after),
        )
    }

    @Test
    fun effort_is_configurable_per_request() {
        val cheap = assembler.request("c", emptyList(), effort = OutputConfig.Effort.LOW)
        val careful = assembler.request("c", emptyList(), effort = OutputConfig.Effort.HIGH)

        assertEquals(
            OutputConfig.Effort.LOW,
            cheap.outputConfig().orElseThrow().effort().orElseThrow(),
        )
        assertEquals(
            OutputConfig.Effort.HIGH,
            careful.outputConfig().orElseThrow().effort().orElseThrow(),
        )
    }

    /**
     * The overridable half of the prefix.
     *
     * `instructions` exists so a caller can swap the standing brief — inline
     * completion wants a different one from the chat panel. It was accepted and
     * then dropped on the floor once; nothing failed, because a request built
     * with the default instructions is still a valid request that gets a good
     * answer. Only the callers who passed something would have noticed, and
     * they would have noticed as "the model ignores my brief".
     */
    @Test
    fun the_standing_instructions_can_be_replaced() {
        val params = assembler.request("c", emptyList(), instructions = "Answer only in Kotlin.")

        assertEquals("Answer only in Kotlin.", systemText(params).first())
    }

    @Test
    fun every_request_carries_adaptive_thinking_and_the_tools() {
        val params = assembler.request("c", emptyList())

        assertTrue(
            "thinking should be adaptive",
            params.thinking().orElseThrow().isAdaptive(),
        )
        assertEquals(
            listOf("list_files", "read_file", "grep", "edit_file"),
            params.tools().orElseThrow().map { it.tool().orElseThrow().name() },
        )
    }
}
