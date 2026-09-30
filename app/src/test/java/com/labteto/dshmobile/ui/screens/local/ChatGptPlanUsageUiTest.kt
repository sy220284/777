package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanUsageUiTest {
    @Test
    fun recognizesModelAndAuthorizationRecoveryErrors() {
        assertTrue(isChatGptModelRecoveryError("当前 ChatGPT 模型已不可用，请重新选择模型"))
        assertTrue(isChatGptModelRecoveryError("ChatGPT 账户授权不可用，请重新连接"))
        assertTrue(isChatGptModelRecoveryError("当前模型来源与请求不一致，请重新选择模型"))
        assertFalse(isChatGptModelRecoveryError("普通网络错误"))
    }

    @Test
    fun recognizesPlanUsageLimitAndUnavailableErrors() {
        assertTrue(isChatGptPlanUsageError("ChatGPT 套餐用量请求达到当前限制。你的套餐总额度可能仍有剩余"))
        assertTrue(isChatGptPlanUsageError("CHATGPT_PLAN_USAGE_UNAVAILABLE"))
        assertFalse(isChatGptPlanUsageError("普通网络错误"))
        assertFalse(isChatGptPlanUsageError(null))
    }
}
