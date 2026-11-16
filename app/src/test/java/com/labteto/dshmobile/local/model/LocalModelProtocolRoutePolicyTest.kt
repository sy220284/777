package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LocalModelProtocolRoutePolicyTest {
    private val miniMax = "https://api.minimaxi.com"
    private val model = "MiniMax-M3"

    @Test fun onlyProviderVerifiedProtocolsAreShownAndUnknownCustomGatewaysAreUnchanged() {
        val c = LocalModelProtocol.CHAT_COMPLETIONS
        val r = LocalModelProtocol.RESPONSES
        val a = LocalModelProtocol.ANTHROPIC_MESSAGES
        assertEquals(listOf(a, c),
            LocalModelPresets.editorProtocolsFor(model, "$miniMax/anthropic/v1"))
        assertEquals("MiniMax-M3",
            LocalModelPresets.editablePresetFor(model, "$miniMax/v1")?.model)
        assertEquals(LocalModelProtocol.ANTHROPIC_MESSAGES,
            LocalModelPresets.editablePresetFor(model, "$miniMax/v1")?.protocol)
        assertEquals(listOf(262_144, 1_048_576),
            LocalModelPresets.editorContextWindowOptionsFor("k3.1-pro", "https://api.kimi.com/coding/v1"))
        assertEquals(emptyList<Int>(),
            LocalModelPresets.editorContextWindowOptionsFor(model, "$miniMax/v1"))
        assertEquals("$miniMax/v1",
            LocalModelProtocolRoutePolicy.baseUrlFor("MiniMax-M2.7",
                "$miniMax/anthropic/v1", LocalModelProtocol.CHAT_COMPLETIONS))
        assertEquals(listOf(c),
            LocalModelProtocolRoutePolicy.supportedProtocols("MiniMax-M2.7", "$miniMax/v1"))
        assertEquals(listOf(r, c),
            LocalModelProtocolRoutePolicy.supportedProtocols("k3.1-pro", "https://api.kimi.com/coding/v1"))
        assertEquals(listOf(r, c),
            LocalModelProtocolRoutePolicy.supportedProtocols("deepseek-v4-pro", "https://api.deepseek.com"))
        assertEquals(listOf(a),
            LocalModelProtocolRoutePolicy.supportedProtocols("claude-sonnet-5-5", "https://api.anthropic.com/v1"))
        assertEquals(LocalModelProtocol.entries.toList(),
            LocalModelProtocolRoutePolicy.supportedProtocols(model, "https://proxy.example/v1"))
        assertEquals("https://proxy.example/v1",
            LocalModelProtocolRoutePolicy.baseUrlFor(model, "https://proxy.example/v1", r))
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelProtocolRoutePolicy.baseUrlFor(model, "$miniMax/anthropic/v1", r)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalModelProtocolRoutePolicy.baseUrlFor("k3", "https://api.kimi.com/coding/v1", a)
        }
    }

    @Test fun miniMaxProtocolSwitchChangesOnlyProviderPathAndRetainsExactAccountOrigin() {
        for (root in listOf(miniMax, "https://api.minimaxi.com:443", "https://api.minimax.cn")) {
            val messages = "$root/anthropic/v1"
            val chat = LocalModelProtocolRoutePolicy.baseUrlFor(
                model, messages, LocalModelProtocol.CHAT_COMPLETIONS)
            assertEquals("$root/v1", chat)
            assertEquals("$root/v1/chat/completions",
                LocalModelProtocolRoutePolicy.requestEndpoint(model, chat, LocalModelProtocol.CHAT_COMPLETIONS))
            assertEquals(messages,
                LocalModelProtocolRoutePolicy.baseUrlFor(model, chat, LocalModelProtocol.ANTHROPIC_MESSAGES))
            assertEquals("$root/anthropic/v1/messages",
                LocalModelProtocolRoutePolicy.requestEndpoint(model, messages, LocalModelProtocol.ANTHROPIC_MESSAGES))
            assertTrue(LocalModelProtocolRoutePolicy.sameCredentialOrigin(messages, chat))
            assertEquals(LocalModelProtocol.CHAT_COMPLETIONS,
                LocalModelPresets.find(model, chat)?.protocol)
            assertEquals(LocalModelProtocol.ANTHROPIC_MESSAGES,
                LocalModelPresets.find(model, messages)?.protocol)
        }
        for (other in listOf(
            "https://api.minimax.cn/v1",
            "https://api.minimaxi.com.evil.example/v1",
            "https://proxy.example/v1",
            "http://api.minimaxi.com/v1",
            "https://api.minimaxi.com:8443/v1",
        )) assertFalse(LocalModelProtocolRoutePolicy.sameCredentialOrigin("$miniMax/anthropic/v1", other))
    }

    @Test fun editorProtocolSwitchKeepsSavedProfileAndKeyThroughConnectionAndWireEncoding() = runBlocking {
        val saved = LocalModelProfile(
            "saved-mm-account", model, "$miniMax/anthropic/v1",
            provider = "MiniMax", protocol = LocalModelProtocol.ANTHROPIC_MESSAGES,
            displayName = "旧配置",
        )
        val target = LocalModelProtocolRoutePolicy.baseUrlFor(
            model, saved.baseUrl, LocalModelProtocol.CHAT_COMPLETIONS)
        val reads = mutableListOf<String>()
        val draft = resolveLocalModelApiKeyDraft(
            "", model, target, LocalModelProtocol.CHAT_COMPLETIONS,
            listOf(saved), { id -> reads += id; if (id == saved.id) "saved-key" else null },
            profileId = saved.id,
        )
        assertEquals(saved.id, draft.profile.id)
        assertEquals(saved.displayName, draft.profile.displayName)
        assertEquals("saved-key", draft.key)
        assertEquals(listOf(saved.id), reads)
        assertEquals(target, draft.profile.baseUrl)
        assertNotEquals(saved.routeFingerprint(), draft.profile.routeFingerprint())

        val seen = mutableListOf<String>()
        val json = Json { ignoreUnknownKeys = true }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request().url.toString()
            assertEquals("Bearer saved-key", chain.request().header("Authorization"))
            val data = """{"choices":[{"message":{"role":"assistant","content":"完成"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":1,"completion_tokens":1}}"""
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val reply = DeepSeekClient(http, json).complete(
            apiKey = draft.key, baseUrl = draft.profile.baseUrl, model = model,
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "你好") }),
            tools = JsonArray(emptyList()),
        )
        assertEquals("完成", reply.content)
        assertEquals(listOf("$miniMax/v1/chat/completions"), seen)
    }

    @Test fun oldDeepSeekFullChatEndpointSwitchesToNativeResponsesWithoutChangingTheSavedAccount() = runBlocking {
        val oldUrl = "https://api.deepseek.com/v1/chat/completions"
        val saved = LocalModelProfile("old-deepseek", "deepseek-flash", oldUrl,
            protocol = LocalModelProtocol.CHAT_COMPLETIONS)
        assertEquals(LocalModelProtocol.CHAT_COMPLETIONS,
            LocalModelPresets.find(saved.model, oldUrl)?.protocol)
        assertEquals("deepseek-flash",
            LocalModelPresets.editablePresetFor(saved.model, oldUrl)?.model)
        assertEquals(listOf(LocalModelProtocol.RESPONSES, LocalModelProtocol.CHAT_COMPLETIONS),
            LocalModelPresets.editorProtocolsFor(saved.model, oldUrl))
        val newUrl = LocalModelPresets.editorBaseUrlForProtocol(
            saved.model, oldUrl, LocalModelProtocol.RESPONSES)
        assertEquals("https://api.deepseek.com", newUrl)
        assertEquals("https://api.deepseek.com/responses",
            LocalModelPresets.editorRequestEndpointFor(saved.model, newUrl, LocalModelProtocol.RESPONSES))
        assertTrue(LocalModelProtocolRoutePolicy.sameCredentialOrigin(oldUrl, newUrl))
        val draft = resolveLocalModelApiKeyDraft("", saved.model, newUrl,
            LocalModelProtocol.RESPONSES, listOf(saved),
            { id -> if (id == saved.id) "old-key" else error("wrong credential") },
            profileId = saved.id)
        assertEquals(saved.id, draft.profile.id)
        assertEquals("old-key", draft.key)
        assertEquals(LocalModelProtocol.RESPONSES, draft.profile.protocol)
        assertEquals(newUrl, draft.profile.baseUrl)
        assertNotEquals(saved.routeFingerprint(), draft.profile.routeFingerprint())
        assertFalse(LocalModelProtocolRoutePolicy.sameCredentialOrigin(oldUrl,
            "https://api.deepseek.com.evil.example/v1"))
    }

    @Test fun legacySavedProtocolRemainsSelectedWhenCallerOmitsOptionalProtocol() = runBlocking {
        val source = LocalModelProfile(
            "legacy-deepseek", "deepseek-flash", "https://api.deepseek.com",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        val draft = resolveLocalModelApiKeyDraft(
            "", source.model, source.baseUrl, null, listOf(source),
            { id -> if (id == source.id) "legacy-key" else null },
        )
        assertEquals(source.id, draft.profile.id)
        assertEquals("legacy-key", draft.key)
        assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, draft.profile.protocol)
        assertEquals(source.baseUrl, draft.profile.baseUrl)

        val oldMiniMaxRoute = LocalModelProfile(
            "legacy-mm-route", "MiniMax-M3", "https://api.minimaxi.com/anthropic/v1",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
        )
        val repaired = resolveLocalModelApiKeyDraft(
            "", oldMiniMaxRoute.model, oldMiniMaxRoute.baseUrl, null,
            listOf(oldMiniMaxRoute),
            { id -> if (id == oldMiniMaxRoute.id) "same-key" else null },
        )
        assertEquals(oldMiniMaxRoute.id, repaired.profile.id)
        assertEquals("same-key", repaired.key)
        assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, repaired.profile.protocol)
        assertEquals("https://api.minimaxi.com/v1", repaired.profile.baseUrl)
    }

    @Test fun structuredOutputCapabilityAndGatewayAdmissionMatchTheSameDeepSeekRoute() {
        val r = LocalModelProtocol.RESPONSES
        val deepSeek = LocalModelPresets.runtimeCapabilitiesFor(
            "deepseek-v4-pro", "https://api.deepseek.com", r)
        assertTrue(deepSeek.structuredOutput)
        assertTrue(deepSeek.strictSchema)
        for ((model, url) in listOf(
            "k3" to "https://api.kimi.com/coding/v1",
            "gpt-6-sol" to "https://api.openai.com/v1",
            "custom" to "https://proxy.example/v1",
        )) {
            val capabilities = LocalModelPresets.runtimeCapabilitiesFor(model, url, r)
            assertFalse(capabilities.structuredOutput)
            assertFalse(capabilities.strictSchema)
        }
        assertFalse(LocalModelPresets.runtimeCapabilitiesFor("deepseek-v4-pro",
            "https://api.deepseek.com", r, LocalModelAuthKind.CHATGPT_PLAN).structuredOutput)
    }

    @Test fun miniMaxStandard443PortCanQueryQuotaAndDisplayMediaCapabilities() {
        val profile = LocalModelProfile("mm-443", model,
            "https://api.minimaxi.com:443/anthropic/v1",
            provider = "MiniMax", protocol = LocalModelProtocol.ANTHROPIC_MESSAGES)
        assertTrue(profile.canQueryMiniMaxQuota())
        assertEquals(LocalMediaProvider.MINIMAX, LocalMediaCatalog.providerFor(profile))
        assertTrue(LocalMediaCatalog.offers(listOf(profile)).any { it.kind == LocalMediaKind.VIDEO })
        assertTrue(LocalModelCapability.VIDEO in LocalEffectiveModelContracts.forProfile(profile).clientModalities)
        assertFalse(LocalMediaCatalog.offers(listOf(profile.copy(
            baseUrl = "https://proxy.example/anthropic/v1"))).isNotEmpty())
    }
}
