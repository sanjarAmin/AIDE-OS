package com.osamu.aide.ai.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerUnitTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun newChat_clears_transcript_and_resets_state() {
        val controller = ChatController(
            assistant = FakeAssistant(),
            projectDir = File("/tmp"),
            scope = testScope,
        )

        controller.newChat()

        val state = controller.state.value
        assertTrue(state.entries.isEmpty())
        assertFalse(state.sending)
        assertNull(state.activeStatus)
        assertNull(state.pendingApproval)
        assertNull(state.error)
    }

    @Test
    fun cancelSend_resets_sending_and_activeStatus() = runTest(testDispatcher) {
        val controller = ChatController(
            assistant = FakeAssistant(),
            projectDir = File("/tmp"),
            scope = this,
        )

        controller.send("Hello assistant")
        // Before completion, cancel
        controller.cancelSend()

        val state = controller.state.value
        assertFalse(state.sending)
        assertNull(state.activeStatus)
        assertNull(state.pendingApproval)
    }

    @Test
    fun tool_entry_holds_input_and_result() {
        val tool = ChatEntry.Tool(
            name = "read_file",
            detail = "app/build.gradle.kts",
            declined = false,
            failed = false,
            input = mapOf("path" to "app/build.gradle.kts"),
            result = "plugins { ... }",
        )

        assertEquals("read_file", tool.name)
        assertEquals("app/build.gradle.kts", tool.detail)
        assertEquals("app/build.gradle.kts", tool.input["path"])
        assertEquals("plugins { ... }", tool.result)
    }

    private class FakeAssistant : Assistant() {
        override fun session(
            projectDir: File,
            approver: Approver,
            extraTools: List<AideTool>,
        ): AiSession? = null

        override fun completer(): InlineCompleter? = null
    }
}
