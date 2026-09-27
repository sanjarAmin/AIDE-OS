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

    /**
     * **A list is one block now, not one block per item.**
     *
     * When each item was its own block the gap between two bullets was the gap
     * between two paragraphs, so a five-item list read as five separate
     * thoughts -- and nesting could not be expressed at all. A bulleted list
     * followed by a numbered one stays two blocks, because rendering them as
     * one would number the bullets.
     */
    @Test
    fun parse_lists() {
        val markdown = """
            - Item 1
            * Item 2
            1. First step
            2. Second step
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(2, blocks.size)

        val bullets = blocks[0] as MarkdownBlock.ListBlock
        assertEquals(false, bullets.ordered)
        assertEquals(listOf("Item 1", "Item 2"), bullets.items.map { it.text })

        val numbered = blocks[1] as MarkdownBlock.ListBlock
        assertEquals(true, numbered.ordered)
        assertEquals(listOf("First step", "Second step"), numbered.items.map { it.text })
        assertEquals(listOf("1", "2"), numbered.items.map { it.marker })
    }

    @Test
    fun a_nested_list_keeps_its_depth() {
        val blocks = parseMarkdownBlocks(
            """
            - outer
              - inner
                - deeper
            """.trimIndent(),
        )

        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(listOf(0, 1, 2), list.items.map { it.depth })
    }

    @Test
    fun a_task_list_carries_its_state() {
        val blocks = parseMarkdownBlocks(
            """
            - [x] done
            - [ ] not done
            - ordinary
            """.trimIndent(),
        )

        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(listOf(true, false, null), list.items.map { it.checked })
        assertEquals(listOf("done", "not done", "ordinary"), list.items.map { it.text })
    }

    @Test
    fun a_wrapped_item_stays_one_item() {
        // Models wrap long bullets. Treating the continuation as a paragraph
        // broke the list in half at that point.
        val blocks = parseMarkdownBlocks(
            """
            - a bullet whose text carries on
              onto the next line
            - a second bullet
            """.trimIndent(),
        )

        val list = blocks.single() as MarkdownBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("a bullet whose text carries on onto the next line", list.items[0].text)
    }

    @Test
    fun a_table_keeps_its_cells_and_alignment() {
        val blocks = parseMarkdownBlocks(
            """
            | Option | Cost | Note |
            |--------|-----:|:----:|
            | minSdk 30 | high | breaks API 26 |
            | gate at runtime | low | preferred |
            """.trimIndent(),
        )

        val table = blocks.single() as MarkdownBlock.Table
        assertEquals(listOf("Option", "Cost", "Note"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("minSdk 30", "high", "breaks API 26"), table.rows[0])
        assertEquals(
            listOf(
                MarkdownBlock.Table.Alignment.START,
                MarkdownBlock.Table.Alignment.END,
                MarkdownBlock.Table.Alignment.CENTER,
            ),
            table.alignments,
        )
    }

    @Test
    fun a_lone_pipe_line_is_a_paragraph() {
        // Without the divider row it is not a table, and treating it as one
        // ate the following lines as rows.
        val blocks = parseMarkdownBlocks("| not | a table |")

        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun a_rule_is_a_rule_and_a_dashed_diff_line_is_not() {
        val blocks = parseMarkdownBlocks(
            """
            before

            ---

            ```diff
            ---------
            ```
            """.trimIndent(),
        )

        assertEquals(1, blocks.count { it is MarkdownBlock.Rule })
        // Inside a fence everything is literal; a rule there would have eaten
        // a line of a diff.
        assertEquals("---------", (blocks.last() as MarkdownBlock.Code).code)
    }

    @Test
    fun a_hashtag_is_not_a_heading() {
        val blocks = parseMarkdownBlocks("#nofilter is not a heading")

        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    /**
     * **Half a document is the normal case while streaming.**
     *
     * An unterminated fence has to render as code immediately, or every answer
     * containing code flashes its backticks before settling.
     */
    @Test
    fun an_unterminated_fence_is_already_a_code_block() {
        val blocks = parseMarkdownBlocks("here it is:\n```kotlin\nfun main() {")

        val code = blocks.last() as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("fun main() {", code.code)
        assertEquals(false, code.complete)
    }

    @Test
    fun a_closed_fence_says_it_is_closed() {
        val code = parseMarkdownBlocks("```\nx\n```").single() as MarkdownBlock.Code

        assertEquals(true, code.complete)
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

        val q = blocks[0] as MarkdownBlock.Quote
        assertEquals("This is a quote", q.text)

        val p = blocks[1] as MarkdownBlock.Paragraph
        assertEquals("This is a normal paragraph with\ntwo lines of text.", p.text)
    }
}
