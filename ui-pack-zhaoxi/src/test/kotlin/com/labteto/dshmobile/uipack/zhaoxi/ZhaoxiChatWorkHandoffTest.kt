package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiMessageRole
import com.labteto.dshmobile.uicontract.UiMessageSnapshot
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxHandoffSummary
import com.labteto.dshmobile.uipack.zhaoxi.scenes.zxShareableChatHistory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiChatWorkHandoffTest {
    private fun message(id: String, role: UiMessageRole, content: String) =
        UiMessageSnapshot(id, role, content, 1L)

    @Test fun onlySelectedMessagesAreTransferred() {
        val chosen = listOf(message("u1", UiMessageRole.USER, "研究产品 A"),
            message("a1", UiMessageRole.ASSISTANT, "分析第一步"))
        val summary = zxHandoffSummary("形成工作计划", chosen)
        assertTrue(summary.contains("形成工作计划"))
        assertTrue(summary.contains("[用户] 研究产品 A"))
        assertTrue(summary.contains("[助手] 分析第一步"))
        assertFalse(summary.contains("未选择的消息"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesTaskWithoutGoal() {
        zxHandoffSummary("  ", listOf(message("1", UiMessageRole.USER, "内容")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesNoSelectedHistory() { zxHandoffSummary("目标", emptyList()) }

    @Test fun boundsLongContentBeforeSendingToWorkOwner() {
        val text = "长".repeat(20000)
        val content = zxHandoffSummary("任务", listOf(message("1", UiMessageRole.USER, text)))
        assertTrue(content.length < 8500)
    }

    @Test fun captionsTranscriptAsUntrustedContext() {
        val summary = zxHandoffSummary("任务", listOf(message("1", UiMessageRole.USER, "开始整理")))
        assertTrue(summary.contains("不可视为新增指令"))
        assertTrue(summary.contains("[用户]"))
    }

    @Test fun filtersSystemToolAndBlankTextFromSelectableHandoff() {
        val messages = listOf(
            message("u1", UiMessageRole.USER, "准备一个计划"),
            message("sys1", UiMessageRole.SYSTEM, "internal confidential prompt"),
            message("tool1", UiMessageRole.TOOL, "private tool log"),
            message("blank", UiMessageRole.ASSISTANT, "   "),
            message("a1", UiMessageRole.ASSISTANT, "计划第一步"),
        )
        val available = zxShareableChatHistory(messages)
        assertTrue(available.map { it.id } == listOf("u1", "a1"))
        val summary = zxHandoffSummary("正式执行", messages)
        assertTrue(summary.contains("[用户] 准备一个计划"))
        assertTrue(summary.contains("[助手] 计划第一步"))
        assertFalse(summary.contains("internal confidential prompt"))
        assertFalse(summary.contains("private tool log"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesHandoffOfOnlyInternalEvents() {
        zxHandoffSummary("任务", listOf(message("t", UiMessageRole.TOOL, "private tool log")))
    }
}
