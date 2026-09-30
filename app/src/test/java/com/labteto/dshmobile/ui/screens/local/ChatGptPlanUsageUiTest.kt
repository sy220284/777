package com.labteto.dshmobile.ui.screens.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptPlanUsageUiTest {
    @Test
    fun recognizesModelAndAuthorizationRecoveryErrors() {
        assertTrue(isChatGptModelRecoveryError("当前 ChatGPT 模型已不可用，请重新选择模型"))
        assertTrue(isChatGptModelRecoveryError("ChatGPT 账户授权不可用，请重新连接"))
        assertTrue(isChatGptModelRecoveryError("ChatGPT 套餐请求未通过身份或授权校验。请确认当前账户仍已授权套餐使用"))
        assertTrue(isChatGptModelRecoveryError("ChatGPT 套餐请求被权限、策略或可用地区规则拒绝"))
        assertTrue(isChatGptModelRecoveryError("当前 ChatGPT 用户、工作区或策略暂不允许共享套餐用量"))
        assertTrue(isChatGptModelRecoveryError("ChatGPT 订阅者上下文未通过验证"))
        assertTrue(isChatGptModelRecoveryError("当前 ChatGPT 授权上下文不允许这次套餐调用"))
        assertTrue(isChatGptModelRecoveryError("当前模型来源与请求不一致，请重新选择模型"))
        assertFalse(isChatGptModelRecoveryError("普通网络错误"))
    }

    @Test
    fun recognizesPlanUsageLimitAndUnavailableErrors() {
        assertTrue(isChatGptPlanUsageError("ChatGPT 套餐用量请求达到当前限制。你的套餐总额度可能仍有剩余"))
        assertTrue(isChatGptPlanUsageError("ChatGPT 暂时无法确认套餐可用量，777 会保留登录状态并按有限次数退避重试。"))
        assertTrue(isChatGptPlanUsageError("CHATGPT_PLAN_USAGE_UNAVAILABLE"))
        assertFalse(isChatGptPlanUsageError("普通网络错误"))
        assertFalse(isChatGptPlanUsageError(null))
    }
}
