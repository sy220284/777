package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.DeepSeekClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class LocalKimiCompatibilityTest {
    private val base = "https://api.moonshot.cn/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val user = listOf(buildJsonObject {
        put("role", "user")
        put("content", "你好")
    })

    @Test fun officialApiModelsAreDistinctFromKimiCodeAndRetiredModels() {
        val models = mapOf(
            "kimi-k3" to 1_000_000,
            "kimi-k2.7-code" to 256_000,
            "kimi-k2.7-code-highspeed" to 256_000,
            "kimi-k2.6" to 256_000,
        )
        for ((id, context) in models) {
            val preset = LocalModelPresets.find(id, base)!!
            assertEquals("Kimi", preset.provider)
            assertEquals(context, preset.contextWindowTokens)
            assertEquals(false, preset.temperatureSupported)
            assertEquals(true, preset.imageInputSupported)
            assertTrue(LocalModelCapability.VIDEO in preset.capabilities)
            assertEquals("https://api.moonshot.cn/v1/models", preset.modelsEndpoint)
            assertEquals(LocalModelProtocol.CHAT_COMPLETIONS, preset.protocol)
            assertEquals(setOf(LocalModelCapability.TEXT, LocalModelCapability.IMAGE),
                LocalModelPresets.clientCapabilitiesFor(id, base))
            assertNull(LocalModelPresets.chatTemperatureRangeFor(id, base))
        }
        assertEquals("Kimi Code",
            LocalModelPresets.find("k3", "https://api.kimi.com/coding/v1")?.provider)
        assertNull(LocalModelPresets.find("kimi-k3", "https://api.kimi.com/coding/v1"))
        assertNull(LocalModelPresets.find("kimi-k2.5", base))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.moonshot.cn.evil.example/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://proxy.example/v1"))
        assertFalse(LocalKimiPolicy.isOfficial("https://api.moonshot.cn/fake/v1"))
        assertTrue(LocalKimiPolicy.isOfficial(base + "/"))
    }

    @Test fun reasoningChoicesRespectK3FloorAndK26SwitchWithoutAffectingProxies() {
        fun policy(model: String, endpoint: String = base) =
            LocalReasoningRequestPolicy.resolve(
                LocalModelProfile("kimi", model, endpoint), withTools = true)
        val k3 = policy("kimi-k3")!!
        assertEquals("low", k3.disabledEffort)
        assertEquals("high", k3.enabledEffort)
        assertEquals("low", k3.lowEffort)
        assertEquals("max", k3.maxEffort)
        assertEquals("none", policy("kimi-k2.6")?.disabledEffort)
        assertNull(policy("kimi-k2.7-code"))
        assertNull(policy("kimi-k2.7-code-highspeed"))
        assertNull(policy("kimi-k3", "https://proxy.example/v1"))
        assertNull(policy("k3", "https://api.kimi.com/coding/v1"))
    }

    @Test fun gatewayDropsFixedTemperatureButPreservesToolSupport() {
        for (model in listOf("kimi-k3", "kimi-k2.6", "kimi-k2.7-code")) {
            val capabilities = LocalModelPresets.runtimeCapabilitiesFor(model, base)
            assertFalse(capabilities.temperature)
            assertTrue(capabilities.toolCalling)
            assertTrue(capabilities.streaming)
            val prepared = prepareLocalModelAdapterRequest(
                LocalResolvedModelRoute("kimi", "Kimi", model, base,
                    LocalModelAuthKind.API_KEY, LocalModelProtocol.CHAT_COMPLETIONS,
                    "test", capabilities),
                user, JsonArray(emptyList()), 0.9, true,
            )
            assertNull(prepared.request.temperature)
        }
    }

    @Test fun completeUsesProviderCorrectThinkingFieldsAndPreservesReplay() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += json.parseToJsonElement(Buffer().also {
                chain.request().body!!.writeTo(it)
            }.readUtf8()).jsonObject
            assertEquals("api.moonshot.cn", chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(
                    """{"choices":[{"message":{"role":"assistant","content":"回答","reasoning_content":"先思考"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":2}}}"""
                        .toResponseBody("application/json".toMediaType())
                ).build()
        }.build()
        val client = DeepSeekClient(http, json)
        val k3 = client.complete("test", base, "kimi-k3", user, JsonArray(emptyList()),
            temperature = 0.9, reasoningEffort = "low")
        assertEquals("low", requests.last()["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(requests.last().containsKey("thinking"))
        assertFalse(requests.last().containsKey("temperature"))
        assertEquals("先思考", k3.reasoning)
        assertEquals(12L, k3.usage.promptTokens)
        assertEquals(2L, k3.usage.cacheHitTokens)

        val canonical = LocalCanonicalModelCodec.canonicalizeReply(
            k3, LocalModelAdapterIds.OPENAI_CHAT, "same-route")
        val sameRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical.canonicalMessage!!), LocalModelAdapterIds.OPENAI_CHAT,
            "same-route").single()
        assertEquals("先思考", sameRoute["reasoning_content"]?.jsonPrimitive?.content)
        val otherRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical.canonicalMessage!!), LocalModelAdapterIds.OPENAI_CHAT,
            "different-route").single()
        assertFalse(otherRoute.containsKey("reasoning_content"))

        client.complete("test", base, "kimi-k2.6", user, JsonArray(emptyList()),
            temperature = 0.9, reasoningEffort = "none")
        assertEquals("disabled", requests.last()["thinking"]?.jsonObject
            ?.get("type")?.jsonPrimitive?.content)
        assertFalse(requests.last().containsKey("reasoning_effort"))
        assertFalse(requests.last().containsKey("temperature"))

        client.complete("test", base, "kimi-k2.6", user, JsonArray(emptyList()),
            reasoningEffort = "high")
        assertEquals("enabled", requests.last()["thinking"]?.jsonObject
            ?.get("type")?.jsonPrimitive?.content)
        assertFalse(requests.last().containsKey("reasoning_effort"))

        client.complete("test", base, "kimi-k2.7-code", user, JsonArray(emptyList()),
            reasoningEffort = null)
        assertFalse(requests.last().containsKey("thinking"))
        assertFalse(requests.last().containsKey("reasoning_effort"))
    }

    @Test fun streamingSeparatesReasoningAndAnswerAndReportsUsage() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val sse = listOf(
            """data: {"choices":[{"delta":{"reasoning_content":"分析"}}]}""",
            """data: {"choices":[{"delta":{"content":"答案"},"finish_reason":"stop"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":15,"completion_tokens":9}}""",
            "data: [DONE]",
        ).joinToString("\n", postfix = "\n")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += json.parseToJsonElement(Buffer().also {
                chain.request().body!!.writeTo(it)
            }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val deltas = mutableListOf<LocalModelDelta>()
        val result = DeepSeekClient(http, json).completeStreaming(
            "test", base, "kimi-k3", user, JsonArray(emptyList()),
            reasoningEffort = "max", onDelta = { deltas += it })
        assertEquals("max", requests.single()["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(requests.single().containsKey("thinking"))
        assertEquals("答案", result.content)
        assertEquals("分析", result.reasoning)
        assertEquals("分析", deltas.joinToString("") { it.reasoning })
        assertEquals("答案", deltas.joinToString("") { it.content })
        assertEquals(15L, result.usage.promptTokens)
        assertEquals(9L, result.usage.completionTokens)
    }
}
