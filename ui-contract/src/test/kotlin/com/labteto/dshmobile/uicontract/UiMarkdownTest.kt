package com.labteto.dshmobile.uicontract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMarkdownTest {
    @Test fun preservesDialogueLineBreaksAndParagraphBoundaries() {
        val blocks = parseUiMarkdown("她说：\n第二行\n\n下一段")
        assertEquals(listOf("她说：", "第二行"), (blocks[0] as UiMarkdownBlock.Paragraph).lines)
        assertEquals(listOf("下一段"), (blocks[1] as UiMarkdownBlock.Paragraph).lines)
    }

    @Test fun unfinishedStreamingCodeFenceStaysReadable() {
        val blocks = parseUiMarkdown("说明\n\n\u0060\u0060\u0060kotlin\nval result = 42")
        assertEquals(2, blocks.size)
        assertEquals("kotlin", (blocks[1] as UiMarkdownBlock.Code).lang)
        assertEquals("val result = 42", (blocks[1] as UiMarkdownBlock.Code).code)
    }

    @Test fun keepsNestedListAndEscapedTablePipes() {
        val list = parseUiMarkdown("- 第一项\n  1. 子项\n- 第二项")[0] as UiMarkdownBlock.MdList
        assertEquals(listOf(0, 1, 0), list.levels)
        assertEquals(listOf(false, true, false), list.numbered)
        val table = parseUiMarkdown("| 标题 | 描述 |\n| --- | --- |\n| a \\| b | 内容 |")
            .single() as UiMarkdownBlock.Table
        assertEquals(listOf("a | b", "内容"), table.rows[0])
    }

    @Test fun inlineMarkupHandlesLinksAndCodeWithoutExecutingIt() {
        val spans = parseUiMarkdownInline("**加粗** *强调* \u0060x\u0060 [来源](https://example.com) ~~删去~~")
        assertTrue(spans.any { it is UiMarkdownInline.Bold })
        assertTrue(spans.any { it is UiMarkdownInline.Italic })
        assertTrue(spans.any { it is UiMarkdownInline.Code })
        assertTrue(spans.any { it is UiMarkdownInline.Link })
        assertTrue(spans.any { it is UiMarkdownInline.Strikethrough })
    }
}
