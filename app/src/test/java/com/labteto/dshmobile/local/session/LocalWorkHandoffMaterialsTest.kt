package com.labteto.dshmobile.local.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkHandoffMaterialsTest {
    private fun row(id: String, role: String, text: String, proactive: Boolean = false, tool: String? = null) =
        LocalHarnessMessage(id = id, role = role, content = text, createdAt = 1L,
            proactive = proactive, toolName = tool)

    @Test
    fun candidatesExcludeScheduledToolRowsAndDeduplicateHistoryOverlap() {
        val old = listOf(row("a", "user", "最初要求"), row("b", "assistant", "建议"),
            row("p", "assistant", "定时问候", proactive = true), row("t", "tool", "工具结果", tool = "run"))
        val live = listOf(row("b", "assistant", "建议"), row("c", "user", "新要求"))
        assertEquals(listOf("a", "b", "c"), workHandoffDialogueCandidates(old, live).map { it.id })
    }

    @Test
    fun onlySelectedMessagesAreAppendedAndEditedSummaryIsPreserved() {
        val candidates = listOf(row("a", "user", "需要的材料"), row("b", "assistant", "不相关"),
            row("t", "tool", "秘密结果", tool = "run"))
        val result = workHandoffSummaryWithSelectedMessages(
            "自定义摘要", "chat-123", candidates, listOf("a", "a", "t", "nonexistent"),
        )
        assertTrue(result.startsWith("自定义摘要"))
        assertTrue(result.contains("来源会话：chat-123"))
        assertEquals(1, "消息ID: a".toRegex().findAll(result).count())
        assertFalse(result.contains("不相关"))
        assertFalse(result.contains("秘密结果"))
        assertEquals("不添加", workHandoffSummaryWithSelectedMessages("不添加", "chat-123", candidates, emptyList()))
    }

    @Test
    fun largeMessagesKeepSourceIdAndMarkExcerpt() {
        val result = workHandoffSummaryWithSelectedMessages(
            "", "chat-123", listOf(row("long", "user", "词".repeat(3000))), listOf("long"),
        )
        assertTrue(result.contains("消息ID: long"))
        assertTrue(result.contains("（节选）"))
        assertTrue(result.length < 1400)
    }
}
