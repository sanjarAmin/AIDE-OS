package com.osamu.aide.editor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the code editor looks, as the user set it.
 *
 * Four settings and no theme. Font size is the one that matters: the editor
 * shipped fixed at 14 sp, which on a phone is a decision made for someone with
 * different eyes and no way to disagree with it. The rest are here because they
 * are one line each in the widget and a person who wants two-space indents
 * wants them today.
 */
data class EditorSettings(
    val fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    val tabWidth: Int = DEFAULT_TAB_WIDTH,
    val showLineNumbers: Boolean = true,
    /**
     * Off by default, and the default is an opinion: code has meaningful
     * indentation and long lines, and wrapping them hides the structure the
     * indentation is there to show. On a phone that opinion is worth
     * overriding, which is why it is a setting rather than a constant.
     */
    val wordWrap: Boolean = false,
    val theme: EditorColorTheme = EditorColorTheme.FOLLOW_SYSTEM,
) {
    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14f
        const val DEFAULT_TAB_WIDTH = 4

        /** Below this the gutter numbers collide; above it two words fit a line. */
        val FONT_SIZE_RANGE = 9f..26f

        val TAB_WIDTHS = listOf(2, 4, 8)
    }
}

/**
 * Which colours the code is drawn in.
 *
 * [FOLLOW_SYSTEM] is the default and was, until this existed, not what happened:
 * the app's chrome followed the system into dark mode and the editor stayed on
 * sora's light scheme, so at night most of the screen was a white rectangle
 * inside a dark app. That is the bug this enum was added to fix; the choice
 * between the other two is the feature.
 */
enum class EditorColorTheme(val displayName: String) {
    FOLLOW_SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    /**
     * Whether to draw dark, given what the system is doing.
     *
     * A function rather than an `if` at the call site because the call site is
     * inside an `AndroidView` update block, where nothing can be tested: this
     * is the whole decision, and [EditorColorThemeTest] is three lines.
     */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        FOLLOW_SYSTEM -> systemIsDark
        DARK -> true
        LIGHT -> false
    }
}

/**
 * [EditorSettings], persisted, and observable while the editor is open.
 *
 * A `StateFlow` rather than a read on composition, because the settings screen
 * and the editor are alive at the same time on a tablet: changing the font size
 * has to reach a widget that was built before the change.
 *
 * `SharedPreferences` rather than a file or DataStore -- four scalars, read once
 * at startup, written by hand. Nothing here justifies more.
 */
class EditorPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<EditorSettings> = _settings.asStateFlow()

    fun update(transform: (EditorSettings) -> EditorSettings) {
        val updated = transform(_settings.value).coerced()
        preferences.edit()
            .putFloat(KEY_FONT_SIZE, updated.fontSizeSp)
            .putInt(KEY_TAB_WIDTH, updated.tabWidth)
            .putBoolean(KEY_LINE_NUMBERS, updated.showLineNumbers)
            .putBoolean(KEY_WORD_WRAP, updated.wordWrap)
            .putString(KEY_THEME, updated.theme.name)
            .apply()
        _settings.value = updated
    }

    /** Back to the shipped defaults, for a font size someone can no longer read. */
    fun reset() = update { EditorSettings() }

    private fun read(): EditorSettings = EditorSettings(
        fontSizeSp = preferences.getFloat(
            KEY_FONT_SIZE,
            EditorSettings.DEFAULT_FONT_SIZE_SP,
        ),
        tabWidth = preferences.getInt(KEY_TAB_WIDTH, EditorSettings.DEFAULT_TAB_WIDTH),
        showLineNumbers = preferences.getBoolean(KEY_LINE_NUMBERS, true),
        wordWrap = preferences.getBoolean(KEY_WORD_WRAP, false),
        // By name, and unrecognised names fall back rather than throw: a value
        // written by a later version has to be survivable by an earlier one.
        theme = preferences.getString(KEY_THEME, null)
            ?.let { name -> EditorColorTheme.entries.firstOrNull { it.name == name } }
            ?: EditorColorTheme.FOLLOW_SYSTEM,
    ).coerced()

    /**
     * Clamped on the way in *and* on the way out.
     *
     * On the way out because a stored value can predate a narrowed range or
     * come from a hand-edited preferences file, and a zero font size is an
     * editor that draws nothing -- which looks like the file failing to open.
     */
    private fun EditorSettings.coerced(): EditorSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(EditorSettings.FONT_SIZE_RANGE),
        tabWidth = tabWidth.takeIf { it in EditorSettings.TAB_WIDTHS }
            ?: EditorSettings.DEFAULT_TAB_WIDTH,
    )

    private companion object {
        const val NAME = "editor-settings"
        const val KEY_FONT_SIZE = "fontSizeSp"
        const val KEY_TAB_WIDTH = "tabWidth"
        const val KEY_LINE_NUMBERS = "showLineNumbers"
        const val KEY_WORD_WRAP = "wordWrap"
        const val KEY_THEME = "theme"
    }
}
