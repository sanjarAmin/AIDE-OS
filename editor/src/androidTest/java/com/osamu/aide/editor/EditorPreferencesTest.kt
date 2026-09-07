package com.osamu.aide.editor

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The settings survive, and a stored value can never make the editor unusable.
 *
 * On a device because `SharedPreferences` is the thing being tested: a fake
 * would prove the data class copies fields.
 */
class EditorPreferencesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        context.getSharedPreferences("editor-settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun a_change_survives_a_new_instance() {
        EditorPreferences(context).update { it.copy(fontSizeSp = 20f, tabWidth = 2) }

        val reopened = EditorPreferences(context).settings.value

        assertEquals(20f, reopened.fontSizeSp, 0f)
        assertEquals(2, reopened.tabWidth)
    }

    /**
     * A stored value outside the range is clamped on the way **out**.
     *
     * Not hypothetical: the range can narrow in a later version, and the file
     * is hand-editable on a rooted device. A zero font size is an editor that
     * draws nothing, which reads as the file failing to open rather than as a
     * setting.
     */
    @Test
    fun a_stored_value_outside_the_range_cannot_blank_the_editor() {
        context.getSharedPreferences("editor-settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putFloat("fontSizeSp", 0f)
            .putInt("tabWidth", 137)
            .commit()

        val settings = EditorPreferences(context).settings.value

        assertEquals(EditorSettings.FONT_SIZE_RANGE.start, settings.fontSizeSp, 0f)
        assertEquals(EditorSettings.DEFAULT_TAB_WIDTH, settings.tabWidth)
    }

    @Test
    fun reset_puts_back_what_the_editor_shipped_with() {
        val preferences = EditorPreferences(context)
        preferences.update { it.copy(fontSizeSp = 26f, wordWrap = true, showLineNumbers = false) }

        preferences.reset()

        assertEquals(EditorSettings(), preferences.settings.value)
        // And it persisted, or the next launch brings the unreadable size back.
        assertEquals(EditorSettings(), EditorPreferences(context).settings.value)
    }
}
