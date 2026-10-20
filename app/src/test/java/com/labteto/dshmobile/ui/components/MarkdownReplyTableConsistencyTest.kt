package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.uicontract.UiMarkdownBlock
import com.labteto.dshmobile.uicontract.parseUiMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReplyTableConsistencyTest {
    @Test fun borderlessPipeTableFollowingNarrativeMustRenderAsTableInBothParsers() {
        val text = "调查已完成。\n名称 | 数量\n--- | ---:\n苹果 | 2\n梨 | 3"
        val native = parseMarkdown(text)
        val shared = parseUiMarkdown(text)
        assertEquals(2, native.size)
        assertEquals(2, shared.size)
        assertTrue(native[0] is MdBlock.Paragraph)
        assertTrue(shared[0] is UiMarkdownBlock.Paragraph)
        assertEquals(listOf("名称", "数量"), (native[1] as MdBlock.Table).header)
        assertEquals(listOf("名称", "数量"), (shared[1] as UiMarkdownBlock.Table).header)
        assertEquals(listOf(listOf("苹果", "2"), listOf("梨", "3")), (native[1] as MdBlock.Table).rows)
        assertEquals(listOf(listOf("苹果", "2"), listOf("梨", "3")), (shared[1] as UiMarkdownBlock.Table).rows)
        assertEquals(TableAlignment.END, (native[1] as MdBlock.Table).alignments[1])
    }

    @Test fun regularPipedAndOneColumnTablesStaySupported() {
        val multi = "| 姓名 | 状态 |\n| :--- | ---: |\n| 张三 | 通过 |"
        val single = "| 当前状态 |\n| --- |\n| 等待确认 |"
        for (text in listOf(multi, single)) {
            assertTrue(parseMarkdown(text).single() is MdBlock.Table)
            assertTrue(parseUiMarkdown(text).single() is UiMarkdownBlock.Table)
        }
    }

    @Test fun escapedPipesAndInlineCodeDoNotBecomeColumns() {
        val text = "数据 \\| 说明\n--- | ---\n保持原样"
        assertFalse(parseMarkdown(text).any { it is MdBlock.Table })
        assertFalse(parseUiMarkdown(text).any { it is UiMarkdownBlock.Table })
        val code = "`a | b`\n--- | ---\n说明"
        assertFalse(parseMarkdown(code).any { it is MdBlock.Table })
        assertFalse(parseUiMarkdown(code).any { it is UiMarkdownBlock.Table })
    }

    @Test fun technicalPathBackslashesMustSurviveTableParsing() {
        val markdown = """
            | 路径 | 编号 |
            | --- | --- |
            | C:\Users\demo\project | 1 |
            | \\server\share\project | 2 |
        """.trimIndent()
        val native = parseMarkdown(markdown).single() as MdBlock.Table
        val shared = parseUiMarkdown(markdown).single() as UiMarkdownBlock.Table
        assertEquals("C:\\Users\\demo\\project", native.rows[0][0])
        assertEquals("\\\\server\\share\\project", native.rows[1][0])
        assertEquals(native.rows, shared.rows)
        // A real escaped pipe is one literal cell, not a new column.
        assertEquals(listOf("A|B", "2"),
            (parseMarkdown("| A\\|B | 2 |\\n| --- | --- |").single() as MdBlock.Table).header)
    }

    @Test fun aRegularSentenceWithPipesButNoSeparatorRemainsParagraph() {
        val text = "A | B\n这是正常说明，不是表格"
        assertEquals(1, parseMarkdown(text).size)
        assertEquals(1, parseUiMarkdown(text).size)
    }
}
