package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.withWorkRuntimeContext
import com.labteto.dshmobile.local.model.workRuntimeContextParts
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
    fun taskSpecificInstructionsCannotPolluteStablePrefix() {
        fun parts(task: String) = workRuntimeContextParts(
            context = "固定用户规则",
            workspacePath = "/workspace",
            model = "claude-sonnet-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            profile = null,
            task = task,
        )
        val sourceCode = parts("读取 GitHub 代码")
        val usage = parts("检查模型 Token 用量")
        val ordinary = parts("写一首小诗")
        org.junit.Assert.assertEquals(sourceCode.stable, usage.stable)
        org.junit.Assert.assertEquals(sourceCode.stable, ordinary.stable)
        assertTrue(sourceCode.dynamic.contains("工作区清点"))
        assertTrue(usage.dynamic.contains("TokenUsageAnalyticsStore"))
        assertFalse(ordinary.dynamic.contains("TokenUsageAnalyticsStore"))
        assertFalse(sourceCode.stable.contains("工作区清点"))
        assertFalse(usage.stable.contains("TokenUsageAnalyticsStore"))
        assertTrue(sourceCode.stable.contains("固定用户规则"))
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
        assertTrue(context.contains("【默认认知与交互方式】"))
        assertTrue(context.contains("每次获取关键工具结果后检查其是否改变原假说"))
        assertTrue(context.contains("需要据此下结论时应继续读取相关原文"))
        assertTrue(context.contains("work_finding"))
        assertTrue(context.contains("重要发现及时向用户自然说明并主动追查"))
        assertTrue(context.contains("允许真实好奇、惊讶和兴奋"))
        assertTrue(context.contains("不得以模型文字代替实际结果"))
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
