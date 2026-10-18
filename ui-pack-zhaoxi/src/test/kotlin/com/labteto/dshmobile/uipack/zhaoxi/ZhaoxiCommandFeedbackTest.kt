package com.labteto.dshmobile.uipack.zhaoxi

import com.labteto.dshmobile.uicontract.UiCommandReceipt
import com.labteto.dshmobile.uicontract.UiMessageEditOutcome
import com.labteto.dshmobile.uipack.zhaoxi.scenes.positiveFeedback
import com.labteto.dshmobile.uipack.zhaoxi.scenes.userHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhaoxiCommandFeedbackTest {
    @Test fun allEditOutcomesHaveReadableDistinctMessages() {
        val expected = mapOf(
            UiMessageEditOutcome.SENT to "已提交修改并开始回复",
            UiMessageEditOutcome.COMMITTED_NOT_STARTED to "修改已保存，暂未开始回复",
            UiMessageEditOutcome.BUSY to "当前会话正在执行，请稍后重试",
            UiMessageEditOutcome.UNAVAILABLE to "此时无法修改消息",
            UiMessageEditOutcome.WRONG_MODE to "当前会话模式不支持该操作",
            UiMessageEditOutcome.UNCONFIGURED to "请先完成模型配置",
            UiMessageEditOutcome.HISTORY_UNAVAILABLE to "历史消息暂不可用，请刷新",
            UiMessageEditOutcome.MESSAGE_MISSING to "消息已不存在，请返回刷新",
            UiMessageEditOutcome.EMPTY to "请输入修改后的消息",
            UiMessageEditOutcome.UNCHANGED to "内容未改变",
            UiMessageEditOutcome.FAILED to "修改失败，请重试",
        )
        assertEquals(UiMessageEditOutcome.entries.toSet(), expected.keys)
        expected.forEach { (result, copy) ->
            val receipt = UiCommandReceipt.MessageEdited(result)
            assertEquals(copy, receipt.userHint())
            assertFalse(receipt.userHint().contains(result.name))
        }
    }

    @Test fun committedEditIsNotMisreportedAsRejectionOrAsFinishedGeneration() {
        for (outcome in listOf(UiMessageEditOutcome.SENT, UiMessageEditOutcome.COMMITTED_NOT_STARTED)) {
            assertTrue(UiCommandReceipt.MessageEdited(outcome).positiveFeedback())
        }
        for (outcome in UiMessageEditOutcome.entries - setOf(
            UiMessageEditOutcome.SENT, UiMessageEditOutcome.COMMITTED_NOT_STARTED)) {
            assertFalse(UiCommandReceipt.MessageEdited(outcome).positiveFeedback())
        }
        assertTrue(UiCommandReceipt.Accepted.positiveFeedback())
        assertTrue(UiCommandReceipt.Queued.positiveFeedback())
    }

    @Test fun emptyFailureStillHasVisibleActionableFeedback() {
        assertEquals("操作失败，请重试", UiCommandReceipt.Failed("").userHint())
        assertFalse(UiCommandReceipt.Failed("").positiveFeedback())
        assertEquals("网络异常", UiCommandReceipt.Failed("网络异常").userHint())
    }
}
