package com.osamu.aide.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision behind the editor's colours.
 *
 * Small enough to look obvious, and it was wrong by omission for the life of
 * the project: nothing consulted the theme at all, so the app's chrome went
 * dark and the editor stayed white. Pinned here because the call site is inside
 * an `AndroidView` update block where nothing can be asserted.
 *
 * **What it follows changed.** The flag is now the *app's* darkness, read from
 * the luminance of its background, rather than the OS's answered separately.
 * The two agreed only because the app's `darkTheme` defaults to the same call;
 * it is a parameter, so anything setting it would have reopened the original
 * bug from the other side.
 */
class EditorColorThemeTest {

    @Test
    fun `matching the app means matching it in both directions`() {
        assertTrue(EditorColorTheme.FOLLOW_SYSTEM.isDark(appIsDark = true))
        assertFalse(EditorColorTheme.FOLLOW_SYSTEM.isDark(appIsDark = false))
    }

    @Test
    fun `an explicit choice overrides the app, which is the point of having one`() {
        assertTrue(EditorColorTheme.DARK.isDark(appIsDark = false))
        assertFalse(EditorColorTheme.LIGHT.isDark(appIsDark = true))
    }

    @Test
    fun `matching the app is the default`() {
        assertTrue(EditorSettings().theme == EditorColorTheme.FOLLOW_SYSTEM)
    }

    /**
     * The stored name is the enum constant, so it must not drift.
     *
     * The label became "Match app" when the meaning was corrected; renaming the
     * constant with it would have silently reset every existing choice back to
     * the default on the next launch.
     */
    @Test
    fun `the persisted names are unchanged`() {
        assertTrue(EditorColorTheme.entries.map { it.name }
            .containsAll(listOf("FOLLOW_SYSTEM", "LIGHT", "DARK")))
    }
}
