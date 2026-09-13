package com.osamu.aide.ui.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.osamu.aide.debugger.DebugState
import com.osamu.aide.debugger.FrameView
import com.osamu.aide.debugger.Location
import com.osamu.aide.debugger.SourceLine
import com.osamu.aide.debugger.VariableView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actions that do nothing, for tests that only look. */
val NO_DEBUG_ACTIONS = DebugActions(
    start = {},
    resume = {},
    stepOver = {},
    stepInto = {},
    stepOut = {},
    stop = {},
    selectFrame = {},
    toggleExpanded = {},
    openBreakpoint = {},
    removeBreakpoint = {},
)

/**
 * The Debug tab, at the width of an ordinary phone.
 *
 * Everything asserted here was found by driving the real app at 1080 px and
 * reading screenshots: Stop scrolled off the right edge as the fifth step
 * button, fifty framework frames pushed the variables below the dock, and a
 * breakpoint's path was ellipsised through its line number.
 */
class DebugPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val root = File("/project")
    private val file = File(root, "src/main/java/com/example/demo/MainActivity.java")

    private fun stopped(headlineFile: String = "MainActivity.java", frames: Int = 50) = DebugUiState(
        session = DebugState.Stopped(
            threadId = 1,
            threadName = "main",
            frames = (0 until frames).map { i ->
                FrameView(
                    frameId = i.toLong(),
                    location = Location(1, 1, 1, 0),
                    title = if (i == 0) "MainActivity.onCreate" else "ActivityThread.frame$i",
                    source = SourceLine("com/example/demo/$headlineFile", 12 + i),
                )
            },
            selectedFrame = 0,
            variables = listOf(
                VariableView("this", "MainActivity", "MainActivity", objectId = 7),
                VariableView("text", "TextView", "TextView", objectId = 8),
            ),
        ),
        breakpoints = setOf(FileBreakpoint(file, 12)),
    )

    private fun show(state: DebugUiState, width: Int = 360, actions: DebugActions = NO_DEBUG_ACTIONS) =
        compose.setContent {
            Box(Modifier.width(width.dp).height(340.dp)) {
                DebugPanel(state = state, actions = actions, projectRoot = root, unavailableReason = null)
            }
        }

    /**
     * Stop keeps its size beside a headline long enough to wrap.
     *
     * Compared against the same button beside a short headline -- a sibling,
     * never the container, since a `Row` never reports bounds past its own
     * edge (`CLAUDE.md`). A squeezed Stop narrows and its label wraps, which
     * shows as a change in *both* dimensions.
     */
    @Test
    fun stop_keeps_its_size_beside_a_long_status() {
        // Side by side in one composition: a test rule sets content once.
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                Box(Modifier.width(360.dp).height(120.dp)) {
                    DebugPanel(stopped(headlineFile = "M.java"), NO_DEBUG_ACTIONS, root, null)
                }
                Box(Modifier.width(360.dp).height(120.dp)) {
                    DebugPanel(
                        stopped(headlineFile = "AVeryLongFileNameThatCannotPossiblyFitBesideTheButton.java"),
                        NO_DEBUG_ACTIONS,
                        root,
                        null,
                    )
                }
            }
        }
        val both = compose.onAllNodesWithContentDescription("Stop debugging").fetchSemanticsNodes()
        assertEquals("expected one Stop per panel", 2, both.size)
        val short = both[0].boundsInRoot
        val long = both[1].boundsInRoot

        assertEquals("Stop was squeezed narrower by a long status", short.width, long.width, 0.5f)
        assertEquals("Stop's label wrapped beside a long status", short.height, long.height, 0.5f)
        assertTrue("Stop is not inside a 360 dp panel: $long", long.right <= 360.dp.value * compose.density.density + 0.5f)
    }

    /**
     * The variables are on screen with fifty frames below them.
     *
     * A lazy list composes only what is visible, so a variable that exists as
     * a node at all is one that was laid out inside the panel.
     */
    @Test
    fun variables_come_before_a_deep_stack() {
        show(stopped(frames = 50))
        compose.onNodeWithContentDescription("text = TextView").assertExists()
        val variables = compose.onNodeWithText("Variables").fetchSemanticsNode().boundsInRoot
        val frames = compose.onNodeWithText("Frames").fetchSemanticsNode().boundsInRoot
        assertTrue("Frames is above Variables", variables.top < frames.top)
    }

    /** A breakpoint reads as file and line, with the line never the part cut off. */
    @Test
    fun a_breakpoint_row_names_its_file_and_line() {
        show(DebugUiState(breakpoints = setOf(FileBreakpoint(file, 12))))
        compose.onNodeWithText("MainActivity.java:12").assertExists()
    }

    /** Step controls only do anything while stopped; Stop is there whenever a session is. */
    @Test
    fun stepping_is_offered_only_while_stopped() {
        var state by mutableStateOf(DebugUiState(session = DebugState.Running("Running, with 1 breakpoint.")))
        compose.setContent {
            Box(Modifier.width(360.dp).height(340.dp)) {
                DebugPanel(state = state, actions = NO_DEBUG_ACTIONS, projectRoot = root, unavailableReason = null)
            }
        }
        compose.onNodeWithText("Step over").assertIsNotEnabled()
        compose.onNodeWithText("Resume").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Stop debugging").assertIsEnabled()

        state = stopped()
        compose.waitForIdle()
        compose.onNodeWithText("Step over").assertIsEnabled()
    }

    @Test
    fun opening_a_variable_asks_for_its_fields() {
        var opened: Long? = null
        show(stopped(), actions = NO_DEBUG_ACTIONS.copy(toggleExpanded = { opened = it }))
        compose.onNodeWithContentDescription("text = TextView").performClick()
        assertEquals(8L, opened)
    }
}
