package com.osamu.aide.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the toolbar folds its secondary buttons into More.
 *
 * The case that shipped: a phone (411 dp), a file open and unsaved, Debug
 * available -- seven buttons, and the project name drawn as "…".
 */
class ToolbarLayoutTest {

    @Test
    fun a_phone_with_every_button_showing_folds() {
        assertFalse(ToolbarLayout.fitsInline(widthDp = 411, primary = 3, secondary = 3))
        assertFalse(ToolbarLayout.fitsInline(widthDp = 360, primary = 2, secondary = 3))
    }

    @Test
    fun what_is_left_on_a_phone_still_leaves_the_name_room() {
        // Save, Debug, Build and More.
        assertTrue(ToolbarLayout.fitsInline(widthDp = 360, primary = 4, secondary = 0))
    }

    @Test
    fun a_tablet_keeps_everything_inline() {
        assertTrue(ToolbarLayout.fitsInline(widthDp = 800, primary = 3, secondary = 2))
    }
}
