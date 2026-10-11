package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplySuggestionSchedulingPromptTest {
    @Test fun schedulingIdeasStayInsideUserReplySuggestions() {
        val result = enhanceReplySuggestionPromptForScheduling("根据最近对话生成用户下一句可直接发送的建议")
        assertTrue(result.contains("下一句"))
        assertTrue(result.contains("未来的共同计划"))
        assertTrue(result.contains("没有相关语境则保持普通接话建议"))
        assertTrue(result.contains("实际定时执行需用户明确进入自动任务并确认"))
    }
}
