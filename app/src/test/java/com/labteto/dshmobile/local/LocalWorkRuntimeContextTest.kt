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
        )

        assertTrue(context.contains("777 本机 Harness · 工作模式"))
        assertTrue(context.contains("工作区：/workspace"))
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
