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

class LocalMiniMaxCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun route(model: String = "MiniMax-M3", base: String = "https://api.minimax.cn/anthropic/v1") =
        LocalResolvedModelRoute("mm", "MiniMax", model, base, LocalModelAuthKind.API_KEY,
            LocalModelProtocol.ANTHROPIC_MESSAGES, "test", LocalModelPresets.runtimeCapabilitiesFor(model, base))

    @Test fun officialRegionalRoutesShareCapabilitiesWithoutTrustingProxies() {
        for (host in listOf("api.minimax.io", "api.minimax.cn", "api.minimaxi.com")) {
            for (path in listOf("/v1", "/anthropic/v1")) {
                val base = "https://$host$path"
                assertEquals(1_000_000, LocalModelPresets.find("MiniMax-M3", base)?.contextWindowTokens)
                assertEquals(204_800, LocalModelPresets.find("MiniMax-M2.5", base)?.contextWindowTokens)
                assertEquals(false, LocalModelPresets.documentedImageInputSupport("MiniMax-M2.7", base))
                assertEquals(10_000_000L, LocalModelPresets.maxNativeImageBytesFor("MiniMax-M3", base))
            }
        }
        assertNull(LocalModelPresets.find("MiniMax-M3", "https://proxy.example/v1"))
        assertFalse(LocalMiniMaxPolicy.isOfficial("https://api.minimax.io.evil.example/v1"))
    }

    @Test fun providerQuotaErrorIsReportedInsteadOfMissingChoices() {
        val failure = assertThrows(com.labteto.dshmobile.local.LocalModelException::class.java) {
            DeepSeekClient(OkHttpClient(), json).parse("""{"base_resp":{"status_code":1008,"status_msg":"余额不足"}}""")
        }
        assertEquals("MINIMAX_API_1008", failure.code)
        assertTrue(failure.message!!.contains("余额不足"))
        assertFalse(failure.retryable)
    }

    @Test fun controlsDistinguishSwitchableAndMandatoryThinking() {
        val profile = LocalModelProfile("mm", "MiniMax-M3", "https://api.minimax.io/v1", provider = "MiniMax")
        assertEquals("none", LocalReasoningRequestPolicy.resolve(profile, withTools = true)?.disabledEffort)
        assertNull(LocalReasoningRequestPolicy.resolve(profile.copy(model = "MiniMax-M2.7")))
        val preview = LocalReasoningRequestPolicy.resolve(profile.copy(model = "MiniMax-M3.1-Flash-Preview"))!!
        assertEquals("low", preview.disabledEffort)
        assertEquals("max", preview.maxEffort)
    }

    @Test fun anthropicWritesNativeThinkingAndEffortOnlyForSupportedModel() {
        val client = AnthropicMessagesClient(OkHttpClient(), json)
        fun payload(model: String, effort: String?) = client.buildPayload(route(model), emptyList(), emptyList(), 1.0, effort)
        assertEquals("adaptive", payload("MiniMax-M3", "high")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", payload("MiniMax-M3", "none")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(payload("MiniMax-M3", "high").containsKey("output_config"))
        assertFalse(payload("MiniMax-M2.7", "none").containsKey("thinking"))
        assertEquals("max", payload("MiniMax-M3.1-Flash-Preview", "max")["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { payload("MiniMax-M3.1-Flash-Preview", "none") }
    }

    @Test fun streamingThinkingDetailsSurviveToolHistoryOnlyOnSameRoute() = runBlocking {
        var request: JsonObject? = null
        val stream = """
            data: {"choices":[{"delta":{"reasoning_details":[{"index":0,"id":"r1","type":"reasoning.text","format":"MiniMax-response-v1","text":"先"}]}}]}
            data: {"choices":[{"delta":{"reasoning_details":[{"index":0,"text":"核实"}],"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent() + "\n"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            request = json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(stream.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val reply = DeepSeekClient(http, json).completeStreaming(apiKey = "test", model = "MiniMax-M3", messages = emptyList(),
            tools = JsonArray(emptyList()), baseUrl = "https://api.minimax.io/v1", reasoningEffort = "high")
        assertEquals("true", request!!["reasoning_split"]!!.jsonPrimitive.content)
        assertEquals("adaptive", request!!["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("先核实", reply.reasoning)
        val canonical = LocalCanonicalModelCodec.canonicalizeReply(reply, LocalModelAdapterIds.OPENAI_CHAT, "same").canonicalMessage!!
        val same = LocalCanonicalModelCodec.toLegacyMessages(listOf(canonical), LocalModelAdapterIds.OPENAI_CHAT, "same").single()
        assertEquals("先核实", same["reasoning_details"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("r1", same["reasoning_details"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        val changed = LocalCanonicalModelCodec.toLegacyMessages(listOf(canonical), LocalModelAdapterIds.OPENAI_CHAT, "changed").single()
        assertFalse(changed.containsKey("reasoning_details"))
    }
}
