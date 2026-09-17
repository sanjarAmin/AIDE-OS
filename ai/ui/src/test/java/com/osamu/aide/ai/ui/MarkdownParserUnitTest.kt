package com.osamu.aide.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserUnitTest {

    @Test
    fun parse_code_blocks_with_and_without_language() {
        val markdown = """
            Here is some code:
            ```kotlin
            val x = 42
            println(x)
            ```
            And plain code:
            ```
            echo "hello"
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(4, blocks.size)

        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("Here is some code:", (blocks[0] as MarkdownBlock.Paragraph).text)

        assertTrue(blocks[1] is MarkdownBlock.Code)
        val code1 = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code1.language)
        assertEquals("val x = 42\nprintln(x)", code1.code)

        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
        assertEquals("And plain code:", (blocks[2] as MarkdownBlock.Paragraph).text)

        assertTrue(blocks[3] is MarkdownBlock.Code)
        val code2 = blocks[3] as MarkdownBlock.Code
        assertEquals(null, code2.language)
        assertEquals("echo \"hello\"", code2.code)
    }

    @Test
    fun parse_headers() {
        val markdown = """
            # Header 1
            ## Header 2
            ### Header 3
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(3, blocks.size)

        val h1 = blocks[0] as MarkdownBlock.Header
        assertEquals(1, h1.level)
        assertEquals("Header 1", h1.text)

        val h2 = blocks[1] as MarkdownBlock.Header
        assertEquals(2, h2.level)
        assertEquals("Header 2", h2.text)

        val h3 = blocks[2] as MarkdownBlock.Header
        assertEquals(3, h3.level)
        assertEquals("Header 3", h3.text)
    }

    @Test
    fun parse_lists() {
        val markdown = """
            - Item 1
            * Item 2
            1. First step
            2. Second step
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(4, blocks.size)

        val b1 = blocks[0] as MarkdownBlock.BulletItem
        assertEquals("Item 1", b1.text)

        val b2 = blocks[1] as MarkdownBlock.BulletItem
        assertEquals("Item 2", b2.text)

        val n1 = blocks[2] as MarkdownBlock.NumberedItem
        assertEquals("1", n1.number)
        assertEquals("First step", n1.text)

        val n2 = blocks[3] as MarkdownBlock.NumberedItem
        assertEquals("2", n2.number)
        assertEquals("Second step", n2.text)
    }

    @Test
    fun parse_blockquotes_and_paragraphs() {
        val markdown = """
            > This is a quote
            
            This is a normal paragraph with
            two lines of text.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(2, blocks.size)

        val q = blocks[0] as MarkdownBlock.Blockquote
        assertEquals("This is a quote", q.text)

        val p = blocks[1] as MarkdownBlock.Paragraph
        assertEquals("This is a normal paragraph with\ntwo lines of text.", p.text)
    }
}
