package com.osamu.aide.ai.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inline formatting, character by character.
 *
 * **The cases that matter are the ones code produces.** `snake_case` must not
 * become italics, a backtick containing an asterisk must not open an emphasis,
 * and an unclosed marker -- which is what the tail of every streamed message
 * looks like for a moment -- must stay literal rather than swallowing the rest
 * of the sentence.
 */
class InlineMarkdownTest {

    private val colors = InlineColors(
        text = Color.White,
        code = Color.Cyan,
        codeBackground = Color.DarkGray,
        link = Color.Blue,
    )

    private fun render(text: String) = inlineMarkdown(text, colors)

    @Test
    fun `bold and italic are applied and their markers removed`() {
        val bold = render("a **strong** word")
        assertEquals("a strong word", bold.text)
        assertTrue(bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold })

        val italic = render("an *emphasised* word")
        assertEquals("an emphasised word", italic.text)
        assertTrue(italic.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `triple markers are both at once`() {
        val both = render("***very***")

        assertEquals("very", both.text)
        assertTrue(both.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(both.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `inline code keeps its content exactly`() {
        // The old renderer padded the content with spaces for visual breathing
        // room, which ended up in the clipboard and broke an identifier used
        // mid-sentence.
        val rendered = render("call `read_file` first")

        assertEquals("call read_file first", rendered.text)
    }

    @Test
    fun `an asterisk inside code is not emphasis`() {
        val rendered = render("`a * b` and `c * d`")

        assertEquals("a * b and c * d", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `snake case identifiers are left alone`() {
        // The reason `_` only opens emphasis at a word boundary: this is how
        // most of the identifiers in a codebase are spelled.
        val rendered = render("read_file and write_file_now")

        assertEquals("read_file and write_file_now", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `an underscore at a word boundary still emphasises`() {
        val rendered = render("_really_ now")

        assertEquals("really now", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `strikethrough is applied`() {
        val rendered = render("~~gone~~")

        assertEquals("gone", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.textDecoration != null })
    }

    @Test
    fun `a link shows its label and carries its url`() {
        val rendered = render("see [the docs](https://example.com/x) for more")

        assertEquals("see the docs for more", rendered.text)
        assertTrue("no link annotation was attached", rendered.getLinkAnnotations(0, rendered.length).isNotEmpty())
    }

    @Test
    fun `a link with no label falls back to the url`() {
        val rendered = render("[](https://example.com)")

        assertEquals("https://example.com", rendered.text)
    }

    @Test
    fun `an unclosed marker stays literal`() {
        // Every streamed message looks like this for a moment. Swallowing the
        // rest of the text here makes the tail of an answer flicker.
        assertEquals("a **half written", render("a **half written").text)
        assertEquals("an `unclosed span", render("an `unclosed span").text)
        assertEquals("a [partial link](", render("a [partial link](").text)
    }

    @Test
    fun `empty emphasis is literal`() {
        // `****` appears in separator lines models sometimes emit.
        assertEquals("****", render("****").text)
    }

    @Test
    fun `nested emphasis inside bold is kept`() {
        val rendered = render("**bold with *italic* inside**")

        assertEquals("bold with italic inside", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }
}
