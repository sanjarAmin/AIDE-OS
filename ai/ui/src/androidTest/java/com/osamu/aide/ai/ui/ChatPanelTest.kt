package com.osamu.aide.ai.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.osamu.aide.ai.core.AiProviderType
import com.osamu.aide.ai.core.ApprovalRequest
import com.osamu.aide.ai.core.DiffLine
import com.osamu.aide.ai.core.ApprovalScope
import com.osamu.aide.ai.core.ChatEntry
import com.osamu.aide.ai.core.ChatUiState
import com.osamu.aide.ai.core.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the panel puts on screen for a given state.
 *
 * The assertions worth having are the ones about honesty and about width: that
 * a tool call is visible at all, that a failed one does not read like a success,
 * that the approval prompt shows what would be written *before* asking -- and
 * that none of it squeezes at **360 dp**, which is the width CLAUDE.md says to
 * drive before believing a row is fine, and the width at which nine separate
 * defects in this codebase were found.
 *
 * The panel holds no rules of its own; those are `ChatController`'s and are
 * tested in `:ai:core`. Nothing here needs a network.
 */
class ChatPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private var sent: String? = null
    private var approvals = mutableListOf<Pair<Boolean, ApprovalScope>>()
    private var regenerated = 0
    private var edited: Pair<Long, String>? = null

    private fun actions() = ChatActions(
        send = { sent = it },
        approve = { ok, scope -> approvals += ok to scope },
        dismissError = {},
        addKey = {},
        regenerate = { regenerated++ },
        editAndResend = { id, text -> edited = id to text },
        insertCode = {},
    )

    /**
     * Shown at a phone's width, always.
     *
     * The emulator's default is wider than an ordinary small phone, and a row
     * that is fine there can be broken at 360 dp -- the dock's tabs were
     * exactly that. Constraining the composable rather than the device means
     * every test here runs at the width that finds the defect.
     */
    private fun show(state: ChatUiState, width: Int = 360) = compose.setContent {
        Box(Modifier.width(width.dp)) {
            ChatPanel(state = state, actions = actions(), activeFileName = null)
        }
    }

    private fun answer(text: String, streaming: Boolean = false) =
        ChatEntry.FromAssistant(text, streaming = streaming)

    /** Height in dp, the measurement that separates a squeezed row from a fine one. */
    private fun SemanticsNodeInteraction.heightDp(): Float =
        getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }

    private fun SemanticsNodeInteraction.widthDp(): Float =
        getUnclippedBoundsInRoot().let { (it.right - it.left).value }

    // -- the header ---------------------------------------------------------

    @Test
    fun the_assistant_and_its_model_are_both_named() {
        show(ChatUiState(activeProvider = AiProviderType.LOCAL, activeModel = "qwen2.5-coder-1.5b"))

        compose.onNodeWithText("On device").assertIsDisplayed()
        compose.onNodeWithText("qwen2.5-coder-1.5b").assertIsDisplayed()
    }

    /**
     * The menu says which providers can answer before one is chosen.
     *
     * Switching to a provider with no key used to look like it worked, and the
     * failure arrived on the next message -- by which point the switch was
     * several taps back and looked unrelated.
     */
    @Test
    fun the_menu_marks_the_providers_that_need_setting_up() {
        show(
            ChatUiState(
                activeProvider = AiProviderType.GEMINI,
                providersWithKeys = setOf(AiProviderType.GEMINI),
            ),
        )

        compose.onNodeWithTag(IDENTITY_TAG).performClick()

        compose.onNodeWithText("Anthropic").assertIsDisplayed()
        // Every provider but the configured one, exactly. "At least one" --
        // what this checked -- held with the marking inverted, and with it on
        // every provider including the one that works.
        assertEquals(
            AiProviderType.entries.size - 1,
            compose.onAllNodesWithText("Needs setup").fetchSemanticsNodes().size,
        )
    }

    /** A long model name must not push the actions off the row. */
    @Test
    fun a_long_model_name_does_not_squeeze_the_header_actions() {
        show(
            ChatUiState(
                activeProvider = AiProviderType.LOCAL,
                activeModel = "qwen2.5-coder-7b-instruct-q4-k-m-extended",
            ),
        )

        val history = compose.onNodeWithTag(HISTORY_TAG)
        val newChat = compose.onNodeWithTag(NEW_CHAT_TAG)
        // Compared against a sibling control, never the container: a Row clamps
        // to the space it has, so an edge test against the panel is always true.
        assertEquals(newChat.heightDp(), history.heightDp(), 0.5f)
        assertEquals(newChat.widthDp(), history.widthDp(), 0.5f)
        assertTrue("the history button collapsed", history.widthDp() > 24f)
    }

    // -- the empty state ----------------------------------------------------

    @Test
    fun an_empty_panel_says_what_the_assistant_can_do() {
        show(ChatUiState())

        compose.onNodeWithText("Ask about this project").assertIsDisplayed()
        compose.onNodeWithText("What does this do?").assertIsDisplayed()
    }

    /**
     * **Four chips at 360 dp.** A `Row` would lay the last one out 19 dp wide
     * and wrap its label inside it, and `assertIsDisplayed` is true of that
     * chip. Height is what separates the two layouts: a chip too narrow for
     * its label grows taller. This is `CreateProjectDialogTest`'s lesson,
     * applied to the panel that has four of them.
     */
    @Test
    fun the_openings_all_get_their_full_height() {
        show(ChatUiState())

        val heights = listOf("What does this do?", "Find the bug", "Explain a file", "Write a test")
            .map { compose.onNodeWithText(it).heightDp() }

        val first = heights.first()
        heights.forEach { assertEquals("an opening chip wrapped its label", first, it, 1f) }
    }

    @Test
    fun a_provider_that_needs_setting_up_is_offered_the_way_to_do_it() {
        show(ChatUiState(activeProvider = AiProviderType.LOCAL, needsKey = true))

        compose.onNodeWithText("Set up on-device").assertIsDisplayed()
    }

    // -- the transcript -----------------------------------------------------

    @Test
    fun both_sides_of_the_conversation_are_shown() {
        show(
            ChatUiState(
                entries = listOf(ChatEntry.FromUser("why does it fail?"), answer("Because minSdk is 26.")),
            ),
        )

        compose.onNodeWithText("why does it fail?").assertIsDisplayed()
        compose.onNodeWithText("Because minSdk is 26.").assertIsDisplayed()
    }

    @Test
    fun a_tool_call_says_what_ran_and_on_what() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool(
                        name = "read_file",
                        detail = "app/src/main/AndroidManifest.xml",
                        declined = false,
                        failed = false,
                        input = mapOf("path" to "app/src/main/AndroidManifest.xml"),
                        result = "<manifest/>",
                    ),
                ),
            ),
        )

        compose.onNodeWithText("read file").assertIsDisplayed()
        compose.onNodeWithText("app/src/main/AndroidManifest.xml").assertIsDisplayed()
    }

    @Test
    fun opening_a_tool_call_shows_its_output_verbatim() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool("read_file", "M.kt", declined = false, failed = false, result = "fun main() {}"),
                ),
            ),
        )

        compose.onNodeWithTag(TOOL_PEG_TAG).performClick()

        compose.onNodeWithText("fun main() {}").assertIsDisplayed()
    }

    @Test
    fun a_declined_call_says_so_rather_than_looking_finished() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool("edit_file", "M.kt", declined = true, failed = false, result = "Declined."),
                ),
            ),
        )

        compose.onNodeWithTag(TOOL_PEG_TAG).performClick()
        compose.onNodeWithText("Declined.").assertIsDisplayed()
    }

    /** A long path shortens; the chevron and the duration keep their room. */
    @Test
    fun a_long_path_does_not_squeeze_the_tool_row() {
        show(
            ChatUiState(
                entries = listOf(
                    ChatEntry.Tool(
                        name = "read_file",
                        detail = "app/src/main/java/com/osamu/aide/ui/workspace/WorkspaceScreen.kt",
                        declined = false,
                        failed = false,
                        durationMs = 1_500,
                    ),
                    ChatEntry.Tool("read_file", "M.kt", declined = false, failed = false, durationMs = 1_500),
                ),
            ),
        )

        // The same control in a row with a short label, which is the only
        // comparison a Row's own bounds cannot fake: against the container an
        // edge test is true whatever the layout does.
        //
        // **The unmerged tree, or this measures the row.** The chevron sits in
        // the card's clickable Surface, which merges its descendants, so the
        // merged query returned the two full-width cards -- equal whatever the
        // chevron did, and the test passed with the path's weight removed.
        val chevrons = compose.onAllNodesWithContentDescription("Show details", useUnmergedTree = true)
        assertEquals(2, chevrons.fetchSemanticsNodes().size)
        val besideLongPath = chevrons[0].widthDp()
        val besideShortPath = chevrons[1].widthDp()
        assertEquals("the chevron shrank beside a long path", besideShortPath, besideLongPath, 0.5f)
    }

    // -- streaming ----------------------------------------------------------

    @Test
    fun a_streaming_answer_shows_a_caret_and_hides_its_actions() {
        show(ChatUiState(entries = listOf(answer("Partial ans", streaming = true)), sending = true))

        compose.onNodeWithTag(CARET_TAG).assertExists()
        assertEquals(
            "actions were offered on a half-written answer",
            0,
            compose.onAllNodesWithTag(COPY_ANSWER_TAG).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun a_finished_answer_offers_copy_and_try_again() {
        show(
            ChatUiState(
                entries = listOf(ChatEntry.FromUser("q"), answer("Done.")),
                canRegenerate = true,
            ),
        )

        compose.onNodeWithTag(COPY_ANSWER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(REGENERATE_TAG).performClick()

        assertEquals(1, regenerated)
    }

    @Test
    fun the_working_line_says_what_it_is_doing() {
        show(ChatUiState(sending = true, activeStatus = "Running read_file (M.kt)..."))

        compose.onNodeWithTag(STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Running read_file (M.kt)...").assertIsDisplayed()
    }

    // -- the composer -------------------------------------------------------

    @Test
    fun typing_and_sending_reaches_the_callback() {
        show(ChatUiState())

        compose.onNodeWithTag(COMPOSER_TAG).performTextInput("what is this")
        compose.onNodeWithTag(SEND_TAG).performClick()

        assertEquals("what is this", sent)
    }

    @Test
    fun an_empty_draft_cannot_be_sent() {
        show(ChatUiState())

        compose.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
    }

    @Test
    fun a_turn_in_flight_offers_stop_instead_of_send() {
        show(ChatUiState(sending = true))

        compose.onNodeWithTag(STOP_TAG).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(SEND_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun tapping_a_question_puts_it_back_in_the_composer_to_edit() {
        show(ChatUiState(entries = listOf(ChatEntry.FromUser("teh typo"))))

        compose.onNodeWithTag(QUESTION_TAG).performClick()

        compose.onNodeWithText("Editing your message").assertIsDisplayed()
        // Asserted on the field, not by text: the words are now in two places
        // at once -- the question above and the draft below -- which is the
        // point of the feature and makes a bare text matcher ambiguous.
        compose.onNodeWithTag(COMPOSER_TAG).assertTextContains("teh typo")
    }

    /** The field is the label and the send button is the control. */
    @Test
    fun a_long_draft_does_not_squeeze_the_send_button() {
        show(ChatUiState())
        val beforeWidth = compose.onNodeWithTag(SEND_TAG).widthDp()
        val beforeHeight = compose.onNodeWithTag(SEND_TAG).heightDp()

        compose.onNodeWithTag(COMPOSER_TAG)
            .performTextInput("a question that runs on well past the width of a small phone screen")

        assertEquals(beforeWidth, compose.onNodeWithTag(SEND_TAG).widthDp(), 0.5f)
        assertEquals(beforeHeight, compose.onNodeWithTag(SEND_TAG).heightDp(), 0.5f)
    }

    // -- approval -----------------------------------------------------------

    @Test
    fun the_approval_prompt_shows_the_file_and_what_would_change() {
        show(
            ChatUiState(
                pendingApproval = ApprovalRequest(
                    toolName = "edit_file",
                    path = "Main.kt",
                    preview = "",
                    diff = listOf(
                        // Unchanged, and beginning with a minus: the prompt
                        // used to read the leading `-` and draw it as deleted.
                        DiffLine(DiffLine.Kind.CONTEXT, "- a list item", 1),
                        DiffLine(DiffLine.Kind.REMOVED, "fun main() = Unit", null),
                        DiffLine(DiffLine.Kind.ADDED, "fun main() = println(42)", 2),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Change Main.kt?").assertIsDisplayed()
        compose.onNodeWithText("- fun main() = Unit").assertIsDisplayed()
        compose.onNodeWithText("+ fun main() = println(42)").assertIsDisplayed()
        // Drawn with the unchanged row's blank sign in front of its own text.
        compose.onNodeWithText("  - a list item").assertIsDisplayed()
    }

    @Test
    fun a_write_that_changes_nothing_says_so() {
        show(
            ChatUiState(
                pendingApproval = ApprovalRequest(
                    toolName = "edit_file",
                    path = "Main.kt",
                    preview = "",
                    diff = emptyList(),
                ),
            ),
        )

        compose.onNodeWithText("No changes: the file already has exactly this content.").assertIsDisplayed()
    }

    @Test
    fun a_command_is_shown_as_a_command() {
        show(
            ChatUiState(
                pendingApproval = ApprovalRequest("run_shell", "ls -la", "ls -la"),
            ),
        )

        compose.onNodeWithText("Run a command in your project?").assertIsDisplayed()
    }

    @Test
    fun each_answer_reports_its_own_scope() {
        show(ChatUiState(pendingApproval = ApprovalRequest("edit_file", "M.kt", "+ x")))

        compose.onNodeWithTag(ALLOW_ALWAYS_TAG).performClick()

        assertEquals(listOf(true to ApprovalScope.CONVERSATION), approvals)
    }

    @Test
    fun declining_reports_a_decline() {
        show(ChatUiState(pendingApproval = ApprovalRequest("edit_file", "M.kt", "+ x")))

        compose.onNodeWithTag(DECLINE_TAG).performClick()

        assertEquals(listOf(false to ApprovalScope.ONCE), approvals)
    }

    /**
     * **Three buttons at 360 dp.** This is the row most likely to squeeze --
     * "Allow in this chat" is a long label sitting beside two others -- and the
     * consequence of getting it wrong is the worst in the panel: the button
     * that grants standing permission to rewrite files becomes unreadable.
     */
    @Test
    fun the_approval_buttons_all_keep_their_height() {
        show(ChatUiState(pendingApproval = ApprovalRequest("edit_file", "M.kt", "+ x")))

        val heights = listOf(ALLOW_ONCE_TAG, ALLOW_ALWAYS_TAG, DECLINE_TAG)
            .map { compose.onNodeWithTag(it).heightDp() }

        heights.forEach { assertEquals("an approval button wrapped its label", heights[0], it, 1f) }
    }

    // -- errors and history -------------------------------------------------

    @Test
    fun a_failure_offers_the_action_that_usually_fixes_it() {
        show(
            ChatUiState(
                entries = listOf(ChatEntry.FromUser("q")),
                error = "Gemini request failed (429)",
                canRegenerate = true,
            ),
        )

        compose.onNodeWithTag(ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(1, regenerated)
    }

    @Test
    fun the_history_sheet_lists_what_has_been_asked_before() {
        show(
            ChatUiState(
                conversations = listOf(
                    ConversationSummary("c-1", "Why does the release build fail?", System.currentTimeMillis(), 4),
                ),
            ),
        )

        compose.onNodeWithTag(HISTORY_TAG).performClick()

        compose.onNodeWithText("Why does the release build fail?").assertIsDisplayed()
        compose.onNodeWithText("4 messages · just now").assertIsDisplayed()
    }
}
