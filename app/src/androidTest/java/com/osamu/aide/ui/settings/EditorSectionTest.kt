package com.osamu.aide.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.osamu.aide.editor.EditorPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Both switches are on the screen.
 *
 * `Wrap long lines` was not: its `Row` gave the label column no weight, so the
 * text measured at whatever width it wanted and the Switch was laid out in what
 * remained -- which for a two-line detail was less than a Switch, leaving the
 * track running past the right edge and half the touch target outside the
 * window. `Line numbers`, whose detail is one line shorter, sat correctly, so
 * the row *looked* right until the longer one was read.
 *
 * **Compare it against its sibling, not against the container.** The obvious
 * assertion -- the switch's right edge is inside the root's -- passes on the
 * broken layout, for the reason `CreateProjectDialogTest` records: a `Row`
 * hands out what it has left, so nothing it lays out ever reports bounds past
 * the edge. What it does instead is measure the switch at a `maxWidth` of zero
 * and collapse it: `Rect(0, 0, 0, 0)` against the working row's 137x84. So the
 * two switches being the same size is the thing to assert, exactly as that
 * test compares two chips' heights.
 *
 * The section is narrowed to 320 dp so the overflow is a property of the
 * layout rather than of whichever device runs the test.
 */
class EditorSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var preferences: EditorPreferences

    @Before
    fun setUp() {
        preferences = EditorPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private fun switchBounds(title: String) = compose
        .onNodeWithContentDescription(title)
        .fetchSemanticsNode()
        .boundsInRoot

    @Test
    fun a_switch_is_not_squeezed_away_by_a_long_label() {
        compose.setContent {
            Column(Modifier.width(320.dp)) { EditorSection(preferences) }
        }

        val short = switchBounds("Line numbers")
        val long = switchBounds("Wrap long lines")

        assertEquals(
            "the switches are not the same width: $short against $long",
            short.width,
            long.width,
            TOLERANCE,
        )
        assertEquals(
            "the switches are not the same height: $short against $long",
            short.height,
            long.height,
            TOLERANCE,
        )
    }

    private companion object {
        /** Pixels. The two are laid out identically or they are not. */
        const val TOLERANCE = 0.5f
    }
}
