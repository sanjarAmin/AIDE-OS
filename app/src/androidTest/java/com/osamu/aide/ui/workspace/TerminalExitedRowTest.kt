package com.osamu.aide.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.osamu.aide.terminal.TerminalState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Restart survives the sentence beside it.
 *
 * The row that appears when the shell dies puts a variable-length message --
 * "The shell was killed by signal 15." against "The shell exited with status
 * 0." -- next to the only control that brings the shell back, and the message
 * had no `weight`. Compared against the same button in the shorter state, for
 * the reason `CLAUDE.md` records: a `Row` never reports bounds past its own
 * edge, so only a sibling shows the squeeze.
 *
 * One `setContent` and a state the test writes, because a compose rule takes
 * only one call and this needs the panel in two states.
 */
class TerminalExitedRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = TerminalActions(
        type = {},
        typeChar = { _, _ -> },
        sendKey = {},
        interrupt = {},
        restart = {},
        resize = { _, _ -> },
    )

    @Test
    fun the_restart_button_is_the_same_size_whatever_killed_the_shell() {
        // 0: "The shell exited with status 0." 143 is 128 + 15, which the panel
        // reports as killed by a signal and is the longer sentence.
        val status = mutableStateOf(0)
        compose.setContent {
            val exit by status
            Box(Modifier.width(320.dp)) {
                TerminalPanel(
                    state = TerminalUiState(state = TerminalState.Exited(exit)),
                    actions = actions,
                )
            }
        }
        val afterAnExit = restartBounds()

        compose.runOnUiThread { status.value = 143 }
        compose.waitForIdle()
        val afterAKill = restartBounds()

        assertEquals(
            "Restart was squeezed by the longer message: $afterAnExit against $afterAKill",
            afterAnExit.width,
            afterAKill.width,
            0.5f,
        )
    }

    private fun restartBounds() = compose
        .onNodeWithContentDescription("Restart the shell")
        .fetchSemanticsNode()
        .boundsInRoot
}
