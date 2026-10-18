package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uipack.zhaoxi.components.ZxReplyBlock
import com.labteto.dshmobile.uipack.zhaoxi.components.parseZxRichReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Progressive and final replies must share one parser, including incomplete stream chunks. */
class ZhaoxiRichReplyTest {
    @Test fun partialStreamKeepsUnclosedMarkupVisible() {
        val blocks = parseZxRichReply("正在核查 **源码")
        assertEquals(listOf(ZxReplyBlock.Paragraph("正在核查 **源码")), blocks)
    }

    @Test fun finalReplySplitsHeadingsTableAndCodeWithoutToolCommands() {
        val source = "# 核查结果\n\n| 模块 | 状态 |\n| --- | --- |\n| UI | 已接入 |\n\n```kotlin\nval safe = true\n```"
        val blocks = parseZxRichReply(source)
        assertTrue(blocks.first() is ZxReplyBlock.Heading)
        val table = blocks.filterIsInstance<ZxReplyBlock.Table>().single()
        assertEquals(listOf("模块", "状态"), table.headers)
        assertEquals(listOf(listOf("UI", "已接入")), table.rows)
        assertEquals("val safe = true", blocks.filterIsInstance<ZxReplyBlock.Code>().single().source)
    }

    @Test fun oldPlainTextOutputRemainsReadableUnderNewRenderer() {
        assertEquals(listOf(ZxReplyBlock.Paragraph("第一段回复\n仍在同一段")),
            parseZxRichReply("第一段回复\n仍在同一段"))
    }
}
