package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelAuthKind
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalModelGatewayRoutingTest {
    @Test
    fun chatGptPlanAlwaysUsesResponsesRegardlessOfStoredProfileProtocol() {
        val accidentalAnthropic = LocalModelProfile(
            id = "plan",
            model = "gpt-test",
            baseUrl = "https://api.openai.com/v1",
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.RESPONSES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
                profile = accidentalAnthropic,
                model = accidentalAnthropic.model,
                baseUrl = accidentalAnthropic.baseUrl,
            ),
        )
    }

    @Test
    fun apiKeyProfileKeepsItsExplicitProtocol() {
        val profile = LocalModelProfile(
            id = "claude",
            model = "claude-sonnet-5-5",
            baseUrl = "https://api.anthropic.com/v1",
            authKind = LocalModelAuthKind.API_KEY,
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )

        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = profile,
                model = profile.model,
                baseUrl = profile.baseUrl,
            ),
        )
    }

    @Test
    fun presetsRouteEachProviderWithoutChangingExistingCompatibleTransports() {
        val expected = listOf(
            Triple("deepseek-flash", "https://api.deepseek.com", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("MiniMax-M3", "https://api.minimaxi.com/v1", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("k3", "https://api.kimi.com/coding/v1", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple("glm-5.3-flash", "https://open.bigmodel.cn/api/paas/v4", LocalModelProtocol.CHAT_COMPLETIONS),
            Triple(
                "gemini-3.8-flash",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                LocalModelProtocol.CHAT_COMPLETIONS,
            ),
            Triple(
                "qwen3.8-max",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                LocalModelProtocol.CHAT_COMPLETIONS,
            ),
            Triple("gpt-6-astra", "https://api.openai.com/v1", LocalModelProtocol.RESPONSES),
            Triple("claude-sonnet-5-5", "https://api.anthropic.com/v1", LocalModelProtocol.ANTHROPIC_MESSAGES),
        )

        expected.forEach { (model, baseUrl, protocol) ->
            assertEquals(
                protocol,
                resolveLocalModelProtocol(
                    authKind = LocalModelAuthKind.API_KEY,
                    profile = null,
                    model = model,
                    baseUrl = baseUrl,
                ),
            )
        }
    }

    @Test
    fun customApiKeyRouteDefaultsToChatCompletions() {
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            resolveLocalModelProtocol(
                authKind = LocalModelAuthKind.API_KEY,
                profile = null,
                model = "custom-model",
                baseUrl = "https://custom.example/v1",
            ),
        )
    }
}
