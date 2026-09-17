package com.osamu.aide.ui.workspace

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher that replaced the drawer's edge swipe.
 *
 * **Why the swipe went.** `ModalNavigationDrawer` opens on a drag from the left
 * edge, and what sits under that edge is an editor that scrolls horizontally --
 * so dragging towards the start of a long line opened the file tree instead.
 * The gesture and the content wanted the same pixels and the gesture won, which
 * made the editor feel broken rather than the drawer feel helpful.
 * `gesturesEnabled = false` in `WorkspaceScreen` is the fix.
 *
 * **What this test does not cover, and how it was checked.** Which control
 * appears in which state -- this tab with no file open, a folder button in the
 * bottom row while editing -- is decided inside `EditorArea`, a private
 * composable with thirty parameters that cannot be rendered in isolation
 * without a fixture larger than the thing under test. It was verified by
 * driving the app on the emulator: with no file open the tab is at the bottom
 * left and the row is absent; opening a file removes the tab and puts the
 * folder button first in the row, before Go to definition; a swipe from the
 * left edge over the editor no longer opens the drawer, and the button still
 * does. Saying so here is worth more than a test that renders a stub and
 * asserts about the stub.
 */
@RunWith(AndroidJUnit4::class)
class ProjectTreeTabTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun it_says_what_it_opens_and_calls_back_when_tapped() {
        var taps = 0
        compose.setContent { ProjectTreeTab(onClick = { taps++ }) }

        // Labelled in words, not by icon alone. This is the empty state's only
        // affordance, and the gesture that used to do the same job left no
        // trace on screen at all -- which is why nobody found it.
        compose.onNodeWithText("Project files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show project files").assertExists()

        compose.onNodeWithText("Project files").assertHasClickAction().performClick()
        assertEquals("the tab did not report the tap", 1, taps)
    }

    /**
     * It stays against the left edge, which is the whole metaphor.
     *
     * The tab stands in for a drawer that used to slide out from the edge, and
     * it reads as a sliver of that sheet only while it touches the edge. An
     * inset of a few dp would make it an ordinary floating button that happens
     * to be near a corner.
     *
     * The animation nudges it outward by a few pixels, so this allows for that
     * rather than demanding exactly zero -- an assertion of `left == 0` would
     * pass or fail depending on which frame the test sampled.
     */
    @Test
    fun it_sits_against_the_left_edge() {
        compose.setContent { ProjectTreeTab(onClick = {}) }

        val bounds = compose.onNodeWithText("Project files").getUnclippedBoundsInRoot()
        assertTrue(
            "the tab is inset from the left edge by ${bounds.left}, so it no longer " +
                "reads as the drawer it replaces",
            bounds.left.value < 24f,
        )
    }
}
