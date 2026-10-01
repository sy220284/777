package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptContextTest {
    @Test
    fun workPromptGroundsWorkspaceAndRequiresRealChecks() {
        val prompt = workSystemPrompt("/data/user/0/com.labteto.dshmobile/files/workspace", false)

        assertTrue(prompt.contains("当前工作区：/data/user/0/com.labteto.dshmobile/files/workspace"))
        assertTrue(prompt.contains("实际工具结果确认"))
        assertTrue(prompt.contains("禁止用口头推测模拟检查或执行"))
    }

    @Test
    fun workRuntimeContextUsesFrozenRouteIdentityWithoutLeakingCredentialReference() {
        val profile = LocalModelProfile(
            id = "profile-plan",
            model = "gpt-5.6-luna",
            baseUrl = "https://api.openai.com/v1",
            provider = "OpenAI",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            credentialRef = "account-private-reference",
        )

        val context = withWorkRuntimeContext(
            context = "用户规则：完成后复查。",
            workspacePath = "/workspace",
            model = "gpt-5.6-luna",
            baseUrl = "https://api.openai.com/v1",
            profile = profile,
        )

        assertTrue(context.contains("777 本机 Harness · 工作模式"))
        assertTrue(context.contains("当前路由模型：gpt-5.6-luna"))
        assertTrue(context.contains("模型提供方：OpenAI"))
        assertTrue(context.contains("认证来源：ChatGPT 套餐"))
        assertTrue(context.contains("模型协议：RESPONSES"))
        assertTrue(context.contains("environment_info"))
        assertTrue(context.contains("读取工作区文件使用 read"))
        assertTrue(context.contains("用户规则：完成后复查。"))
        assertFalse(context.contains("account-private-reference"))
    }
}
