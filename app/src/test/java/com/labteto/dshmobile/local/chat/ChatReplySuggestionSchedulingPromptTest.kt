package com.labteto.dshmobile.local.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplySuggestionSchedulingPromptTest {
    @Test fun schedulingIdeasStayInsideUserReplySuggestions() {
        val base = "根据最近对话生成用户下一句可直接发送的建议"
        assertEquals(base, enhanceReplySuggestionPromptForScheduling(base, "今天好忙"))
        val relevant = enhanceReplySuggestionPromptForScheduling(base, "明天一起去走走")
        assertTrue(relevant.contains("下一句"))
        assertTrue(relevant.contains("未来的共同计划"))
        assertTrue(relevant.contains("不创建自动任务"))
    }
}
