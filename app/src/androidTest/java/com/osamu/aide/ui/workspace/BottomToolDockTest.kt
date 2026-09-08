package com.osamu.aide.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The dock's five tabs fit a small phone, and Close survives them.
 *
 * At 360 dp -- a perfectly ordinary phone -- the tab row took the whole width
 * and what was left was squeezed: "Terminal" wrapped into a column of one- and
 * two-letter fragments, and the close button was measured at nothing and
 * disappeared, leaving the dock covering half the editor with no way to shut
 * it. The row scrolls now, and keeps a weight so the button is measured first.
 *
 * Asserted by comparing tabs against each other, which is what
 * `CreateProjectDialogTest` established: a squeezed label wraps and so grows
 * *taller*, and height is the only thing that separates the two layouts.
 */
class BottomToolDockTest {

    @get:Rule
    val compose = createComposeRule()

    private fun dockAt(width: Int) = compose.setContent {
        Box(Modifier.width(width.dp)) {
            BottomToolDock(
                buildState = BuildUiState(),
                problems = emptyList(),
                gitState = GitUiState(),
                gitActions = GitActions(
                    stage = {},
                    unstage = {},
                    setMessage = {},
                    commit = {},
                    push = {},
                    openSettings = {},
                    initialise = {},
                    showDiff = { _, _ -> },
                    dismissDiff = {},
                ),
                terminalState = TerminalUiState(),
                terminalActions = TerminalActions(
                    type = {},
                    typeChar = { _, _ -> },
                    sendKey = {},
                    interrupt = {},
                    restart = {},
                    resize = { _, _ -> },
                ),
                onDiagnosticClick = {},
                onFixDiagnostic = {},
                onLaunchIntent = {},
                onClose = {},
            )
        }
    }

    @Test
    fun every_tab_keeps_its_label_on_one_line_on_a_small_phone() {
        dockAt(360)

        val heights = ToolTab.entries.map { tab ->
            compose.onNodeWithText(tab.title).fetchSemanticsNode().boundsInRoot.height
        }

        assertEquals(
            "the tabs are not all the same height, so one has wrapped: " +
                ToolTab.entries.map { it.title }.zip(heights).toString(),
            heights.min(),
            heights.max(),
            TOLERANCE,
        )
    }

    @Test
    fun close_is_still_there_when_the_tabs_do_not_fit() {
        dockAt(360)

        val close = compose
            .onNodeWithContentDescription("Close dock")
            .fetchSemanticsNode()
            .boundsInRoot

        // It was laid out at zero and drew nothing at all, which is a dock the
        // user cannot dismiss.
        assertTrue("Close dock was squeezed to $close", close.width > 0f && close.height > 0f)
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
