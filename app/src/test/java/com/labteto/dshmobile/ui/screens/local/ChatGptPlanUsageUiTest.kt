package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanUsageUiTest {
    @Test
    fun recognizesPlanUsageLimitAndUnavailableErrors() {
        assertTrue(isChatGptPlanUsageError("ChatGPT 套餐用量已达到当前上限"))
        assertTrue(isChatGptPlanUsageError("CHATGPT_PLAN_USAGE_UNAVAILABLE"))
        assertFalse(isChatGptPlanUsageError("普通网络错误"))
        assertFalse(isChatGptPlanUsageError(null))
    }
}
