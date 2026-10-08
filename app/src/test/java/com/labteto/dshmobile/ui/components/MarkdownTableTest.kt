package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {
    @Test
    fun parsesHeaderRowsAndAlignment() {
        val blocks = parseMarkdown(
            """
            | name | score | note |
            | :--- | ---: | :---: |
            | **Alice** | 10 | ok |
            """.trimIndent(),
        )
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("name", "score", "note"), table.header)
        assertEquals(listOf("**Alice**", "10", "ok"), table.rows.single())
        assertEquals(
            listOf(TableAlignment.START, TableAlignment.END, TableAlignment.CENTER),
            table.alignments,
        )
    }

    @Test
    fun escapedAndInlineCodePipesStayInsideOneCell() {
        assertEquals(
            listOf("a | b", "`x|y`", "z"),
            splitTableRow("| a \\| b | `x|y` | z |"),
        )
    }

    @Test
    fun separatorRequiresRealDashCells() {
        assertTrue(isTableSeparator("| --- | :---: | ---: |"))
    }
    @Test
    fun semanticCalloutsIdentifyImportantAndWarningWithoutChangingOrdinaryQuotes() {
        assertEquals(MarkdownCalloutKind.QUOTE,
            markdownCalloutKind(listOf("这里是一段需要清晰阅读的引用")))
        assertEquals(MarkdownCalloutKind.IMPORTANT,
            markdownCalloutKind(listOf("[!IMPORTANT]", "这里是重点")))
        assertEquals(MarkdownCalloutKind.WARNING,
            markdownCalloutKind(listOf("[!CAUTION]", "这里是注意事项")))
        assertEquals(MarkdownCalloutKind.TIP,
            markdownCalloutKind(listOf("[!TIP]", "这里是建议")))
    }

    @Test
    fun unfinishedStreamingQuoteAndCodeFenceRemainReadable() {
        val blocks = parseMarkdown(
            """
            > [!IMPORTANT]
            > 应注意这一点

            ```kotlin
            val x = 1
            """.trimIndent(),
        )
        assertEquals(2, blocks.size)
        val quote = blocks[0] as MdBlock.Blockquote
        assertEquals(MarkdownCalloutKind.IMPORTANT, markdownCalloutKind(quote.lines))
        assertEquals("val x = 1", (blocks[1] as MdBlock.Code).code)
    }

}
