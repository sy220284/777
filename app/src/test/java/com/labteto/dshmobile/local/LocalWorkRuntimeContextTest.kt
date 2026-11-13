package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.withWorkRuntimeContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkRuntimeContextTest {
    @Test
    fun chatGptPlanRuntimeFactsUseFrozenRouteWithoutLeakingCredentialReference() {
        val profile = LocalModelProfile(
            id = "profile-plan",
            model = "gpt-5.6-luna",
            baseUrl = "https://api.openai.com/v1",
            provider = "OpenAI",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            // Deliberately stale: the effective ChatGPT-plan route must still be Responses.
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            credentialRef = "account-private-reference",
        )

        val context = withWorkRuntimeContext(
            context = "用户规则：完成后复查。",
            workspacePath = "/workspace",
            model = "gpt-5.6-luna",
            baseUrl = "https://api.openai.com/v1",
            profile = profile,
            task = "检查模型配置与额度",
            configuredProfiles = listOf(profile, LocalModelProfile(
                id = "secondary-route", model = "MiniMax-M3",
                baseUrl = "https://api.minimaxi.com/v1", provider = "MiniMax",
                credentialRef = "secret-key-reference",
            )),
        )

        assertTrue(context.contains("777 本机 Harness · 工作模式"))
        assertFalse(context.contains("用户指令优先级"))
        assertTrue(context.contains("工作区：/workspace"))
        assertTrue(context.contains("当前路由模型：gpt-5.6-luna"))
        assertTrue(context.contains("模型提供方：OpenAI"))
        assertTrue(context.contains("认证来源：ChatGPT 套餐"))
        assertTrue(context.contains("模型协议：RESPONSES"))
        assertTrue(context.contains("已保存模型档案"))
        assertTrue(context.contains("MiniMax-M3（profileId=secondary-route）"))
        assertFalse(context.contains("secret-key-reference"))
        assertFalse(context.contains("TokenUsageAnalyticsStore"))
        assertTrue(context.contains("用户规则：完成后复查。"))
        assertFalse(context.contains("account-private-reference"))
    }
    @Test
    fun businessBudgetAndSalesStatsDoNotLoadModelTokenLedger() {
        for (task in listOf("写一份预算表", "统计销售额")) {
            val prompt = withWorkRuntimeContext("", "/workspace", "model", "", null, task = task)
            assertFalse(prompt.contains("TokenUsageAnalyticsStore"))
        }
        val modelUsage = withWorkRuntimeContext("", "/workspace", "model", "", null, task = "检查模型用量")
        assertTrue(modelUsage.contains("TokenUsageAnalyticsStore"))
    }

    @Test
    fun workRuntimeAsksForGroundedPublicProgressWithoutForcingToolChatter() {
        val context = withWorkRuntimeContext(
            context = "", workspacePath = "/workspace", model = "test-model",
            baseUrl = "", profile = null, task = "检查界面代码与测试",
        )
        assertTrue(context.contains("关键阶段可以先公开说明"))
        assertTrue(context.contains("用户语言自然表达"))
        assertTrue(context.contains("未完成只按真实工具回执判断"))
        assertFalse(context.contains("每次工具调用必须"))
    }

    @Test
    fun ordinaryRequestSkipsIrrelevantCapabilityAndUsageGuides() {
        val context = withWorkRuntimeContext(
            context = "当前需求", workspacePath = "/workspace", model = "test-model",
            baseUrl = "", profile = null, task = "写一个短诗",
        )
        assertFalse(context.contains("list_subagent_models"))
        assertFalse(context.contains("TokenUsageAnalyticsStore"))
        assertFalse(context.contains("session_event_search"))
        assertFalse(context.contains("媒体工具"))
        assertFalse(context.contains("用户指令优先级"))
        assertTrue(context.contains("capability_search"))
    }

}
