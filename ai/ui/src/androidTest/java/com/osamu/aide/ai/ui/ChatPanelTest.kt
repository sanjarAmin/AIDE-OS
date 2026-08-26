package com.osamu.aide.ai.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the panel puts on screen for a given state.
 *
 * The assertions worth having here are the ones about honesty: that a tool call
 * is visible at all, that a failed call does not read like a successful one,
 * and that the approval prompt shows what would be written before asking. The
 * panel holds no rules of its own -- those are `ChatController`'s, tested in
 * `:ai:core` -- so nothing here needs a network.
 */
class ChatPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: ChatUiState,
        onSend: (String) -> Unit = {},
        onApproval: (Boolean) -> Unit = {},
    ) = compose.setContent {
        ChatPanel(
            state = state,
            onSend = onSend,
            onApproval = onApproval,
            onDismissError = {},
            onAddKey = {},
        )
    }

    /**
     * An empty panel has to say what the thing does.
     *
     * "Reads your files, edits them once you confirm" is not guessable from a
     * blank box with a text field under it, and the confirmation half is the
     * fact that makes trying it reasonable.
     */
    @Test
    fun an_empty_panel_explains_what_the_assistant_can_do() {
        show(ChatUiState())

        compose.onNodeWithText("Ask about your code").assertExists()
        compose.onNodeWithText("confirm the change", substring = true).assertExists()
    }

    @Test
    fun both_sides_of_the_conversation_are_shown() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.FromUser("what does Main.kt do?"),
                    ChatEntry.FromAssistant("It prints hi."),
                ),
            ),
        )

        compose.onNodeWithText("what does Main.kt do?").assertExists()
        compose.onNodeWithText("It prints hi.").assertExists()
    }

    /** A tool call the user cannot see is one they have to take on trust. */
    @Test
    fun a_tool_call_is_visible_with_what_it_touched() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool("read_file", "src/Main.kt", declined = false, failed = false),
                ),
            ),
        )

        compose.onNodeWithText("ran read_file — src/Main.kt").assertExists()
    }

    /**
     * A failed call must not read like a successful one.
     *
     * That is how a user comes to believe an answer that was based on a file
     * the assistant never opened.
     */
    @Test
    fun a_failed_call_reads_differently_from_a_successful_one() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool("read_file", "../secrets", declined = false, failed = true),
                ),
            ),
        )

        compose.onNodeWithText("could not run read_file — ../secrets").assertExists()
        compose.onNodeWithText("ran read_file — ../secrets").assertDoesNotExist()
    }

    @Test
    fun a_declined_call_says_so() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool("edit_file", "src/Main.kt", declined = true, failed = false),
                ),
            ),
        )

        compose.onNodeWithText("declined edit_file — src/Main.kt").assertExists()
    }

    /** The prompt has to show the change, or "Allow" is a coin toss. */
    @Test
    fun the_approval_prompt_shows_the_file_and_the_content() {
        var answer: Boolean? = null
        show(
            state = ChatUiState(
                sending = true,
                pendingApproval = ApprovalRequest(
                    toolName = "edit_file",
                    path = "src/Main.kt",
                    preview = "fun main() = println(42)",
                ),
            ),
            onApproval = { answer = it },
        )

        compose.onNodeWithText("Write src/Main.kt?").assertExists()
        compose.onNodeWithText("fun main() = println(42)").assertExists()

        compose.onNodeWithText("Allow").performClick()
        assertEquals(true, answer)
    }

    @Test
    fun declining_reports_a_decline() {
        var answer: Boolean? = null
        show(
            state = ChatUiState(
                sending = true,
                pendingApproval = ApprovalRequest("edit_file", "src/Main.kt", "gone"),
            ),
            onApproval = { answer = it },
        )

        compose.onNodeWithText("Don't").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun typing_and_sending_reaches_the_callback() {
        val sent = mutableListOf<String>()
        show(ChatUiState(), onSend = { sent += it })

        compose.onNodeWithText("Ask about this project").performTextInput("hello")
        compose.onNodeWithContentDescription("Send").performClick()

        assertEquals(listOf("hello"), sent)
    }

    @Test
    fun an_empty_draft_cannot_be_sent() {
        show(ChatUiState())

        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    /** No key is a prompt, not an error. */
    @Test
    fun a_missing_key_offers_a_way_to_add_one() {
        show(ChatUiState(needsKey = true))

        compose.onNodeWithText("Add key").assertExists()
        assertTrue(true)
    }
}
