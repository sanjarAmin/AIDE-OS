package com.osamu.aide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision behind the editor's colours.
 *
 * Small enough to look obvious, and it was wrong by omission for the life of
 * the project: nothing consulted the system at all, so the app's chrome went
 * dark and the editor stayed white. Pinned here because the call site is inside
 * an `AndroidView` update block where nothing can be asserted.
 */
class EditorColorThemeTest {

    @Test
    fun `following the system means following it in both directions`() {
        assertTrue(EditorColorTheme.FOLLOW_SYSTEM.isDark(systemIsDark = true))
        assertFalse(EditorColorTheme.FOLLOW_SYSTEM.isDark(systemIsDark = false))
    }

    @Test
    fun `an explicit choice overrides the system, which is the point of having one`() {
        assertTrue(EditorColorTheme.DARK.isDark(systemIsDark = false))
        assertFalse(EditorColorTheme.LIGHT.isDark(systemIsDark = true))
    }

    @Test
    fun `following the system is the default`() {
        assertTrue(EditorSettings().theme == EditorColorTheme.FOLLOW_SYSTEM)
    }
}
