package com.osamu.aide.editor

import io.github.rosemoe.sora.editor.ts.TsThemeBuilder
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Maps tree-sitter capture names onto the editor's colour slots.
 *
 * Every grammar's queries use their own vocabulary -- `@function.method` in
 * Java, `@keyword.return` in Kotlin, `@tag` in XML -- so this is one table
 * across all of them rather than a table per language. A capture nothing here
 * names renders in the normal text colour, which is why an unfamiliar grammar
 * degrades to "mostly plain" rather than to "wrong colours".
 *
 * Colours are not chosen here: these are slots in sora's [EditorColorScheme],
 * and what they resolve to is the app's theme.
 */
object EditorTheme {

    /** Keywords, and the words that behave like them in other grammars. */
    private val KEYWORDS = arrayOf(
        "keyword", "keyword.function", "keyword.return", "conditional",
        "repeat", "include", "exception", "label", "boolean",
    )

    private val LITERALS = arrayOf(
        "string", "string.escape", "string.regex", "string.special.key",
        "escape", "character", "number", "float", "constant", "constant.builtin",
    )

    private val FUNCTIONS =
        arrayOf("function", "function.method", "function.builtin", "constructor")

    /** Types read as the structure of a file, so they carry extra emphasis. */
    private val TYPES = arrayOf("type", "type.builtin", "namespace", "tag")

    private val VARIABLES = arrayOf("variable", "variable.builtin", "parameter", "property")

    private val PUNCTUATION =
        arrayOf("operator", "punctuation.delimiter", "punctuation.bracket")

    fun applyTo(builder: TsThemeBuilder): Unit = with(builder) {
        style(EditorColorScheme.KEYWORD, bold = true).applyTo(KEYWORDS)
        style(EditorColorScheme.LITERAL).applyTo(LITERALS)
        style(EditorColorScheme.COMMENT, italics = true).applyTo("comment")
        style(EditorColorScheme.FUNCTION_NAME).applyTo(FUNCTIONS)
        style(EditorColorScheme.IDENTIFIER_NAME, bold = true).applyTo(TYPES)
        style(EditorColorScheme.IDENTIFIER_VAR).applyTo(VARIABLES)
        style(EditorColorScheme.ANNOTATION).applyTo("attribute")
        style(EditorColorScheme.OPERATOR).applyTo(PUNCTUATION)
    }

    /**
     * Every capture this table colours. The coverage test reads it, and fails
     * on any entry no grammar emits -- a misspelt capture colours nothing and
     * reports nothing, so nothing else would notice.
     */
    val CAPTURES: Set<String> =
        (KEYWORDS + LITERALS + FUNCTIONS + TYPES + VARIABLES + PUNCTUATION).toSet() +
            setOf("comment", "attribute")

    private fun style(colorId: Int, bold: Boolean = false, italics: Boolean = false): Long =
        TextStyle.makeStyle(colorId, 0, bold, italics, false)
}
