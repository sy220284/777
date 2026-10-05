package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelCapability
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProfile
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.model.LocalModelToolCallingMode
import com.labteto.dshmobile.local.model.LocalPromptCacheMode
import com.labteto.dshmobile.local.model.apiKeyProfileForRoute
import com.labteto.dshmobile.local.model.canBackDeepSeekSearch
import com.labteto.dshmobile.local.model.modelProfileId
import com.labteto.dshmobile.local.model.usesResponsesTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelProfilesTest {
    @Test fun clientCapabilityTagsOnlyAdvertiseImplementedInputs() {
        LocalModelPresets.entries.forEach { preset ->
            assertFalse(LocalModelPresets.clientCapabilitiesFor(preset.model, preset.baseUrl).any {
                it == LocalModelCapability.VIDEO || it == LocalModelCapability.AUDIO || it == LocalModelCapability.MUSIC
            })
        }
        assertEquals(null, LocalModelPresets.samplingTemperatureFor("gpt-5.6", "https://api.openai.com/v1", 0.85))
        assertEquals(10_000_000L, LocalModelPresets.maxNativeImageBytesFor("MiniMax-M3", "https://api.minimaxi.com/v1"))
        assertTrue(LocalModelPresets.entries.any { it.model == "claude-sonnet-5-5" })
        assertFalse(LocalModelPresets.entries.any { it.model == "claude-sonnet-5" })
    }

    @Test fun transportAndSearchCapabilitiesFollowProfileContract() {
        val chat = LocalModelProfile("chat", "model", "https://provider.example/v1")
        val responses = chat.copy(id = "responses", protocol = LocalModelProtocol.RESPONSES)
        val plan = chat.copy(id = "plan", baseUrl = "https://api.openai.com/v1", authKind = LocalModelAuthKind.CHATGPT_PLAN)
        val deepSeek = chat.copy(id = "deepseek", model = "deepseek-flash", baseUrl = "https://api.deepseek.com")
        assertFalse(chat.usesResponsesTransport())
        assertTrue(responses.usesResponsesTransport())
        assertTrue(plan.usesResponsesTransport())
        assertTrue(deepSeek.canBackDeepSeekSearch())
        assertFalse(chat.canBackDeepSeekSearch())
        assertFalse(plan.canBackDeepSeekSearch())
        assertEquals(LocalModelProtocol.RESPONSES, LocalModelPresets.find("gpt-6-astra", "https://api.openai.com/v1")?.protocol)
    }


    @Test fun migratedOfficialClaudeRouteReusesStableApiCredentialProfileId() {
        val officialBase = "https://api.anthropic.com/v1"
        val legacy = LocalModelProfile(
            id = modelProfileId("claude-sonnet-5", officialBase),
            model = "claude-sonnet-5-5",
            baseUrl = officialBase,
            provider = "Claude",
            protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
        )
        val custom = LocalModelProfile(
            id = modelProfileId("claude-sonnet-5", "https://proxy.example/v1"),
            model = "claude-sonnet-5",
            baseUrl = "https://proxy.example/v1",
            provider = "自定义",
        )

        assertEquals(
            legacy.id,
            listOf(legacy, custom).apiKeyProfileForRoute("claude-sonnet-5-5", officialBase)?.id,
        )
        assertEquals(
            custom.id,
            listOf(legacy, custom).apiKeyProfileForRoute("claude-sonnet-5", "https://proxy.example/v1")?.id,
        )
    }

    @Test fun sameModelOnDifferentServicesHasDifferentCredentials() {
        val first = modelProfileId("shared-model", "https://service-a.example/v1")
        val second = modelProfileId("shared-model", "https://service-b.example/v1")
        assertNotEquals(first, second)
        assertEquals(first, modelProfileId(" shared-model ", "https://service-a.example/v1/"))
    }

    @Test fun representativeProvidersKeepTheirDeclaredTransportProtocols() {
        val chatCompletionRoutes = listOf(
            "deepseek-flash" to "https://api.deepseek.com",
            "MiniMax-M3" to "https://api.minimaxi.com/v1",
            "gemini-3.8-flash" to "https://generativelanguage.googleapis.com/v1beta/openai",
            "qwen3.8-max" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
        )
        chatCompletionRoutes.forEach { (model, baseUrl) ->
            assertEquals(
                LocalModelProtocol.CHAT_COMPLETIONS,
                LocalModelPresets.protocolFor(model, baseUrl),
            )
        }
        assertEquals(
            LocalModelProtocol.RESPONSES,
            LocalModelPresets.protocolFor("gpt-6-astra", "https://api.openai.com/v1"),
        )
        assertEquals(
            LocalModelProtocol.ANTHROPIC_MESSAGES,
            LocalModelPresets.protocolFor("claude-sonnet-5-5", "https://api.anthropic.com/v1"),
        )
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            LocalModelPresets.protocolFor("custom-model", "https://custom.example/v1"),
        )
    }

    @Test fun samplingControlsFollowModelCapabilitiesWithoutChangingOtherProviders() {
        assertEquals(
            null,
            LocalModelPresets.samplingTemperatureFor(
                "gpt-6-astra",
                "https://api.openai.com/v1",
                0.85,
            ),
        )
        assertEquals(
            0.85,
            LocalModelPresets.samplingTemperatureFor(
                "gpt-6-sol",
                "https://api.openai.com/v1",
                0.85,
            ),
        )
        assertEquals(
            0.85,
            LocalModelPresets.samplingTemperatureFor(
                "deepseek-flash",
                "https://api.deepseek.com",
                0.85,
            ),
        )
        assertEquals(
            0.85,
            LocalModelPresets.samplingTemperatureFor(
                "gemini-3.8-flash",
                "https://generativelanguage.googleapis.com/v1beta/openai",
                0.85,
            ),
        )
    }

    @Test fun providerPresetsUseDomesticRoutesWithoutRegionSuffixes() {
        val providers = LocalModelPresets.entries.map { it.provider }
        assertFalse(providers.any { it.contains("海外") || it.contains("国际") || it.contains("中国") })

        val routes = LocalModelPresets.entries.map { it.baseUrl }
        assertFalse(routes.any { it.startsWith("https://api.minimax.io") })
        assertFalse(routes.any { it.startsWith("https://api.kimi.ai") })
        assertFalse(routes.any { it.startsWith("https://dashscope-us.aliyuncs.com") })

        assertTrue(
            LocalModelPresets.entries
                .filter { it.provider == "MiniMax" }
                .all { it.baseUrl == "https://api.minimaxi.com/v1" },
        )
        assertTrue(
            LocalModelPresets.entries
                .filter { it.provider == "Kimi Code" }
                .all { it.baseUrl == "https://api.kimi.com/coding/v1" },
        )
        assertTrue(
            LocalModelPresets.entries
                .filter { it.provider == "通义千问" }
                .all { it.baseUrl == "https://dashscope.aliyuncs.com/compatible-mode/v1" },
        )
    }

    @Test fun currentProviderPresetsExposeRoutesAndDocumentedCapabilities() {
        val deepSeekFlash = LocalModelPresets.find("deepseek-flash", "https://api.deepseek.com")!!
        assertTrue(LocalModelCapability.IMAGE in deepSeekFlash.capabilities)
        assertEquals(true, deepSeekFlash.imageInputSupported)
        assertEquals("https://api.deepseek.com/models", deepSeekFlash.modelsEndpoint)

        val deepSeekPro = LocalModelPresets.find("deepseek-v4-pro", "https://api.deepseek.com")!!
        assertFalse(LocalModelCapability.IMAGE in deepSeekPro.capabilities)
        assertEquals(false, deepSeekPro.imageInputSupported)

        val miniMax = LocalModelPresets.find("MiniMax-M3", "https://api.minimaxi.com/v1")!!
        assertTrue(LocalModelCapability.IMAGE in miniMax.capabilities)
        assertTrue(LocalModelCapability.VIDEO in miniMax.capabilities)
        assertEquals(true, miniMax.imageInputSupported)
        assertEquals("https://api.minimaxi.com/v1/chat/completions", miniMax.chatEndpoint)

        val miniMaxText = LocalModelPresets.find("MiniMax-M2.7", "https://api.minimaxi.com/v1")!!
        assertEquals(setOf(LocalModelCapability.TEXT), miniMaxText.capabilities)
        assertEquals(false, miniMaxText.imageInputSupported)

        val kimi = LocalModelPresets.find("k3", "https://api.kimi.com/coding/v1")!!
        assertTrue(LocalModelCapability.IMAGE in kimi.capabilities)
        assertTrue(LocalModelCapability.VIDEO in kimi.capabilities)
        assertEquals(true, kimi.imageInputSupported)

        val kimi256k = LocalModelPresets.find("k3-256k", "https://api.kimi.com/coding/v1")!!
        assertTrue(LocalModelCapability.IMAGE in kimi256k.capabilities)
        assertFalse(LocalModelCapability.VIDEO in kimi256k.capabilities)
        assertEquals(true, kimi256k.imageInputSupported)

        val glmVision = LocalModelPresets.find("glm-5.3-flash", "https://open.bigmodel.cn/api/paas/v4")!!
        assertTrue(LocalModelCapability.IMAGE in glmVision.capabilities)
        assertEquals(true, glmVision.imageInputSupported)

        val glm = LocalModelPresets.find("glm-5.3", "https://open.bigmodel.cn/api/paas/v4")!!
        assertTrue(LocalModelCapability.TEXT in glm.capabilities)
        assertFalse(LocalModelCapability.IMAGE in glm.capabilities)
        assertEquals(false, glm.imageInputSupported)
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/models",
            glm.modelsEndpoint,
        )

        val qwen = LocalModelPresets.find(
            "qwen3.8-max",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
        )!!
        assertTrue(LocalModelCapability.IMAGE in qwen.capabilities)
        assertTrue(LocalModelCapability.VIDEO in qwen.capabilities)
        assertEquals(true, qwen.imageInputSupported)

        val qwenOmni = LocalModelPresets.find(
            "qwen3.8-omni-flash",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
        )!!
        assertTrue(LocalModelCapability.AUDIO in qwenOmni.capabilities)
        assertTrue(LocalModelCapability.VIDEO in qwenOmni.capabilities)

        val gpt6 = LocalModelPresets.find("gpt-6-sol", "https://api.openai.com/v1")!!
        assertTrue(LocalModelCapability.IMAGE in gpt6.capabilities)
        assertEquals(true, gpt6.imageInputSupported)
        assertEquals(
            LocalModelToolCallingMode.CHAT_COMPLETIONS_NO_REASONING,
            gpt6.toolCallingMode,
        )
        assertEquals(
            LocalModelToolCallingMode.RESPONSES_ONLY,
            LocalModelPresets.toolCallingModeFor("gpt-6-astra", "https://api.openai.com/v1"),
        )
        val claudeRuntime = LocalModelPresets.runtimeCapabilitiesFor(
            "claude-sonnet-5-5",
            "https://api.anthropic.com/v1",
        )
        assertTrue(claudeRuntime.streaming)
        assertTrue(claudeRuntime.toolCalling)
        assertTrue(claudeRuntime.replay)
        assertEquals(true, claudeRuntime.imageInput)
    }

    @Test fun latestDistinctModelsAreAvailablePerExistingProvider() {
        fun models(provider: String): Set<String> = LocalModelPresets.entries
            .filter { it.provider == provider }
            .map { it.model }
            .toSet()

        assertEquals(
            setOf("MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.7-highspeed"),
            models("MiniMax"),
        )
        assertEquals(
            setOf("k3", "k3-256k", "kimi-for-coding", "kimi-for-coding-highspeed"),
            models("Kimi Code"),
        )
        assertEquals(
            setOf("glm-5.3-flash", "glm-5.3", "glm-5.2", "glm-5-turbo"),
            models("智谱 GLM"),
        )
        assertEquals(
            setOf("gpt-5.6", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna"),
            models("OpenAI"),
        )
        assertEquals(
            setOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite"),
            models("Google Gemini"),
        )
        assertEquals(
            setOf("qwen3.8-omni-flash", "qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus", "qwen-plus"),
            models("通义千问"),
        )
        assertEquals(
            setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1"),
            models("Claude"),
        )
    }

    @Test fun everyPresetHasAUniqueValidRoute() {
        val routes = LocalModelPresets.entries.map { modelProfileId(it.model, it.baseUrl) }
        assertEquals(routes.size, routes.toSet().size)
    }
    @Test fun chatGptPlanAndApiKeyProfilesNeverCollide() {
        val apiKey = modelProfileId(
            "gpt-test",
            "https://api.openai.com/v1",
            LocalModelAuthKind.API_KEY,
            null,
        )
        val firstAccount = modelProfileId(
            "gpt-test",
            "https://api.openai.com/v1",
            LocalModelAuthKind.CHATGPT_PLAN,
            "account-a",
        )
        val secondAccount = modelProfileId(
            "gpt-test",
            "https://api.openai.com/v1",
            LocalModelAuthKind.CHATGPT_PLAN,
            "account-b",
        )

        assertEquals(modelProfileId("gpt-test", "https://api.openai.com/v1"), apiKey)
        assertNotEquals(apiKey, firstAccount)
        assertNotEquals(firstAccount, secondAccount)
    }



    @Test fun promptCachePoliciesAreRouteAndProtocolSpecific() {
        val deepSeek = LocalModelPresets.runtimeCapabilitiesFor(
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
        ).promptCachePolicy
        assertEquals(LocalPromptCacheMode.PREFIX_AUTO, deepSeek.mode)
        assertTrue(deepSeek.preserveToolSurface)
        assertFalse(deepSeek.allowAdaptiveEarlyCompaction)
        assertTrue(deepSeek.reportsHitMissTokens)

        val openAiApi = LocalModelPresets.runtimeCapabilitiesFor(
            model = "gpt-6-astra",
            baseUrl = "https://api.openai.com/v1",
            protocol = LocalModelProtocol.RESPONSES,
            authKind = LocalModelAuthKind.API_KEY,
        ).promptCachePolicy
        assertEquals(LocalPromptCacheMode.OPENAI_RESPONSES, openAiApi.mode)
        assertTrue(openAiApi.supportsStableCacheKey)
        assertTrue(openAiApi.supportsCacheOptions)
        assertFalse(openAiApi.allowAdaptiveEarlyCompaction)

        val plan = LocalModelPresets.runtimeCapabilitiesFor(
            model = "gpt-6-astra",
            baseUrl = "https://api.openai.com/v1",
            protocol = LocalModelProtocol.RESPONSES,
            authKind = LocalModelAuthKind.CHATGPT_PLAN,
        ).promptCachePolicy
        assertEquals(LocalPromptCacheMode.PREFIX_AUTO, plan.mode)
        assertFalse(plan.supportsStableCacheKey)
        assertFalse(plan.supportsCacheOptions)

        val custom = LocalModelPresets.runtimeCapabilitiesFor(
            model = "custom-model",
            baseUrl = "https://proxy.example/v1",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            authKind = LocalModelAuthKind.API_KEY,
        ).promptCachePolicy
        assertEquals(LocalPromptCacheMode.NONE, custom.mode)
        assertFalse(custom.preserveToolSurface)
    }

    @Test fun promptCacheDiagnosticsFollowChatGptPlanRouteIdentityInsteadOfModelName() {
        listOf("gpt-5.6", "gpt-6-astra", "future-plan-model", "totally-new-name").forEach { model ->
            assertTrue(
                LocalModelPresets.runtimeCapabilitiesFor(
                    model = model,
                    baseUrl = "https://api.openai.com/v1",
                    protocol = LocalModelProtocol.RESPONSES,
                    authKind = LocalModelAuthKind.CHATGPT_PLAN,
                ).promptCacheDiagnostics,
            )
        }

        assertFalse(
            LocalModelPresets.runtimeCapabilitiesFor(
                model = "gpt-5.6",
                baseUrl = "https://api.openai.com/v1",
                protocol = LocalModelProtocol.RESPONSES,
                authKind = LocalModelAuthKind.API_KEY,
            ).promptCacheDiagnostics,
        )
        assertFalse(
            LocalModelPresets.runtimeCapabilitiesFor(
                model = "future-plan-model",
                baseUrl = "https://api.openai.com/v1",
                protocol = LocalModelProtocol.CHAT_COMPLETIONS,
                authKind = LocalModelAuthKind.CHATGPT_PLAN,
            ).promptCacheDiagnostics,
        )
    }

}
