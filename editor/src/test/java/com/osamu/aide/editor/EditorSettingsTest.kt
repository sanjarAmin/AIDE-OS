package com.osamu.aide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The clamping, which is the only logic in [EditorSettings].
 *
 * A JVM test because none of it touches Android: the persistence is four
 * `SharedPreferences` scalars and is covered on a device by
 * `EditorPreferencesTest`.
 */
class EditorSettingsTest {

    @Test
    fun `the defaults are the sizes the editor shipped with`() {
        // Changing these silently re-lays-out every file anyone has open, so
        // they are pinned rather than left to whatever the data class says.
        assertEquals(14f, EditorSettings().fontSizeSp, 0f)
        assertEquals(4, EditorSettings().tabWidth)
        assertEquals(true, EditorSettings().showLineNumbers)
        assertEquals(false, EditorSettings().wordWrap)
    }

    @Test
    fun `the font range stays legible at both ends`() {
        // Below 9 sp the gutter numbers collide with the code; above 26 two
        // words fill a phone line. Both are judgements, and both are the reason
        // the slider has a range at all.
        assertEquals(9f, EditorSettings.FONT_SIZE_RANGE.start, 0f)
        assertEquals(26f, EditorSettings.FONT_SIZE_RANGE.endInclusive, 0f)
    }

    @Test
    fun `the tab widths offered are the ones editors offer`() {
        assertEquals(listOf(2, 4, 8), EditorSettings.TAB_WIDTHS)
    }
}
