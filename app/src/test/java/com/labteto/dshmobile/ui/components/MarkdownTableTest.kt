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
}
