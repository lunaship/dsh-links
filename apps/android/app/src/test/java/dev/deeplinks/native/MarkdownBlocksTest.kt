package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    @Test
    fun splitsMermaidFenceAsCode() {
        val blocks = splitMarkdownBlocks(
            """
            前言
            ```mermaid
            graph TD; A-->B
            ```
            后记
            """.trimIndent()
        )
        assertEquals(listOf(MarkdownBlockType.PARAGRAPH, MarkdownBlockType.CODE, MarkdownBlockType.PARAGRAPH), blocks.map { it.type })
        assertEquals("mermaid", blocks[1].lang)
        assertTrue(blocks[1].content.contains("graph TD"))
    }

    @Test
    fun splitsTableAndHeading() {
        val blocks = splitMarkdownBlocks(
            """
            # 标题
            | a | b |
            | --- | --- |
            | 1 | 2 |
            """.trimIndent()
        )
        assertEquals(MarkdownBlockType.HEADING, blocks[0].type)
        assertEquals(1, blocks[0].level)
        assertEquals(MarkdownBlockType.TABLE, blocks[1].type)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), blocks[1].rows)
    }

    @Test
    fun keepsUnsafeImageAsParagraph() {
        val blocks = splitMarkdownBlocks("![x](http://127.0.0.1/a.png)")
        assertEquals(MarkdownBlockType.PARAGRAPH, blocks.single().type)
    }

    @Test
    fun oversizedTableFallsBackToParagraph() {
        val rows = (1..81).joinToString("\n") { "| r$it | value |" }
        val blocks = splitMarkdownBlocks("| a | b |\n| --- | --- |\n$rows")
        assertEquals(MarkdownBlockType.PARAGRAPH, blocks.single().type)
        assertTrue(blocks.single().content.contains("| r81 | value |"))
    }

    @Test
    fun wideTableFallsBackToParagraph() {
        val header = (1..13).joinToString(" | ", "| ", " |") { "c$it" }
        val sep = (1..13).joinToString(" | ", "| ", " |") { "---" }
        val blocks = splitMarkdownBlocks("$header\n$sep")
        assertEquals(MarkdownBlockType.PARAGRAPH, blocks.single().type)
        assertTrue(blocks.single().content.contains("c13"))
    }
}
