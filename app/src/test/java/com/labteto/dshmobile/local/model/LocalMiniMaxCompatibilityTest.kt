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
                assertEquals(1_000_000, LocalModelPresets.find("MiniMax-M3.1-Flash", base)?.contextWindowTokens)
                assertEquals(true, LocalModelPresets.documentedImageInputSupport("MiniMax-M3.1-Flash", base))
                assertEquals(204_800, LocalModelPresets.find("MiniMax-M2.5", base)?.contextWindowTokens)
                assertEquals(false, LocalModelPresets.documentedImageInputSupport("MiniMax-M2.7", base))
                assertEquals(10_000_000L, LocalModelPresets.maxNativeImageBytesFor("MiniMax-M3", base))
            }
        }
        assertNull(LocalModelPresets.find("MiniMax-M3", "https://proxy.example/v1"))
        assertFalse(LocalMiniMaxPolicy.isOfficial("https://api.minimax.io.evil.example/v1"))
    }

    @Test fun miniMaxM3UsesNativeAnthropicProtocolWithoutIgnoredTemperature() {
        for (host in listOf("api.minimaxi.com", "api.minimax.io", "api.minimax.cn")) {
            val base = "https://$host/anthropic/v1"
            val preset = LocalModelPresets.find("MiniMax-M3.1-Flash", base)!!
            assertEquals(LocalModelProtocol.ANTHROPIC_MESSAGES, preset.protocol)
            assertFalse(preset.temperatureSupported)
            val rt = route("MiniMax-M3.1-Flash", base)
            val payload = AnthropicMessagesClient(OkHttpClient(), json).buildPayload(
                rt, emptyList(), emptyList(), temperature = 0.3, reasoningEffort = "max",
            )
            assertFalse(payload.containsKey("temperature"))
            assertEquals("max", payload["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }
        assertEquals(
            LocalModelProtocol.CHAT_COMPLETIONS,
            LocalModelPresets.protocolFor("MiniMax-M3.1-Flash", "https://api.minimaxi.com/v1"),
        )
    }

    @Test fun miniMaxAnthropicUsesBearerWhileClaudeKeepsNativeApiKey() = runBlocking {
        val captured = mutableListOf<okhttp3.Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401)
                .message("Unauthorized")
                .body("invalid key".toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        val client = AnthropicMessagesClient(http, json)
        val miniMax = route("MiniMax-M3.1-Flash", "https://api.minimaxi.com/anthropic/v1")
        runCatching { client.complete(miniMax, emptyList(), emptyList(), null, streaming = true) }
        assertEquals("Bearer test", captured.single().header("Authorization"))
        assertNull(captured.single().header("x-api-key"))
        captured.clear()

        val claude = miniMax.copy(
            provider = "Claude",
            model = "claude-sonnet-5-5",
            baseUrl = "https://api.anthropic.com/v1",
        )
        runCatching { client.complete(claude, emptyList(), emptyList(), null, streaming = true) }
        assertEquals("test", captured.single().header("x-api-key"))
        assertNull(captured.single().header("Authorization"))
    }

    @Test fun nativeMiniMaxStreamKeepsThinkingReplyToolAndSameRouteReplay() = runBlocking {
        val stream = listOf(
            """data: {"type":"message_start","message":{"id":"mm-stream","usage":{"input_tokens":8,"output_tokens":0}}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先检查仓库"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"mm-signature"}}""",
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"正在核查"}}""",
            """data: {"type":"content_block_stop","index":1}""",
            """data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call-1","name":"read","input":{}}}""",
            """data: {"type":"content_block_stop","index":2}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":21}}""",
            """data: {"type":"message_stop"}""",
        ).joinToString("\n", postfix = "\n")
        var capturedUrl: String? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            capturedUrl = chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(stream.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val client = AnthropicMessagesClient(http, json)
        val selected = route("MiniMax-M3.1-Flash", "https://api.minimaxi.com/anthropic/v1")
        val deltas = mutableListOf<LocalModelDelta>()
        val reply = client.complete(selected, emptyList(), emptyList(), null, true, deltas::add, "high")
        assertEquals("https://api.minimaxi.com/anthropic/v1/messages", capturedUrl)
        assertEquals("先检查仓库", reply.reasoning)
        assertEquals("正在核查", reply.content)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("call-1", reply.toolCalls.single().id)
        assertEquals("先检查仓库", deltas.joinToString("") { it.reasoning })
        assertEquals("正在核查", deltas.joinToString("") { it.content })
        val history = LocalCanonicalModelCodec.toHistoryMessage(reply.canonicalMessage!!)
        val restored = LocalCanonicalModelCodec.message(history)
        val same = client.buildPayload(selected, listOf(restored), emptyList(), null)
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("mm-signature", same[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("read", same[2].jsonObject["name"]!!.jsonPrimitive.content)
        val changed = client.buildPayload(
            selected.copy(baseUrl = "https://api.minimax.io/anthropic/v1"),
            listOf(restored), emptyList(), null,
        ).getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertFalse(changed.toString().contains("mm-signature"))
        assertTrue(changed.any { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" })
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
        val stable = LocalReasoningRequestPolicy.resolve(profile.copy(model = "MiniMax-M3.1-Flash"))!!
        assertEquals("low", stable.disabledEffort)
        assertEquals("high", stable.enabledEffort)
        assertEquals("max", stable.maxEffort)
    }

    @Test fun anthropicWritesNativeThinkingAndEffortOnlyForSupportedModel() {
        val client = AnthropicMessagesClient(OkHttpClient(), json)
        fun payload(model: String, effort: String?) = client.buildPayload(route(model), emptyList(), emptyList(), 1.0, effort)
        assertEquals("adaptive", payload("MiniMax-M3", "high")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", payload("MiniMax-M3", "none")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(payload("MiniMax-M3", "high").containsKey("output_config"))
        assertFalse(payload("MiniMax-M2.7", "none").containsKey("thinking"))
        assertEquals("max", payload("MiniMax-M3.1-Flash-Preview", "max")["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("adaptive", payload("MiniMax-M3.1-Flash-Preview", "max")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { payload("MiniMax-M3.1-Flash-Preview", "none") }
        val latest = payload("MiniMax-M3.1-Flash", "high")
        assertEquals("high", latest["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(latest.containsKey("thinking"))
        assertThrows(IllegalArgumentException::class.java) { payload("MiniMax-M3.1-Flash", "none") }
        val nativeOpenAi = buildJsonObject {
            LocalMiniMaxPolicy.writeThinking(this, "MiniMax-M3.1-Flash", "low", anthropic = false)
        }
        assertEquals("low", nativeOpenAi["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(nativeOpenAi.containsKey("thinking"))
    }

    @Test fun stableM31OpenAiRequestUsesDocumentedNativeEffortWithoutLegacySwitch() = runBlocking {
        var captured: JsonObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            captured = json.parseToJsonElement(
                Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(),
            ).jsonObject
            val result = """{"choices":[{"message":{"role":"assistant","content":"完成"},"finish_reason":"stop"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(result.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val reply = DeepSeekClient(http, json).complete(
            apiKey = "test",
            model = "MiniMax-M3.1-Flash",
            baseUrl = "https://api.minimaxi.com/v1",
            messages = emptyList(),
            tools = JsonArray(emptyList()),
            reasoningEffort = "low",
        )
        assertEquals("MiniMax-M3.1-Flash", captured!!["model"]!!.jsonPrimitive.content)
        assertEquals("low", captured!!["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(captured!!.containsKey("thinking"))
        assertFalse(captured!!.containsKey("reasoning_split"))
        assertEquals("完成", reply.content)
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

    @Test fun streamingTaggedContentSeparatesThinkingWithoutLeakingToolNarration() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"content":"<th"}}]}
            data: {"choices":[{"delta":{"content":"ink>用户要看仓库更新，先检查工具"}}]}
            data: {"choices":[{"delta":{"content":"</th","tool_calls":[{"index":0,"id":"call1","type":"function","function":{"name":"read","arguments":"{}"}}]}}]}
            data: {"choices":[{"delta":{"content":"ink>正在核查仓库最新提交"},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent() + "\n"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(stream.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val deltas = mutableListOf<LocalModelDelta>()
        val reply = DeepSeekClient(http, json).completeStreaming(
            apiKey = "test", model = "MiniMax-M3", messages = emptyList(),
            tools = JsonArray(emptyList()), baseUrl = "https://api.minimax.io/v1",
            onDelta = deltas::add,
        )
        assertEquals("正在核查仓库最新提交", reply.content)
        assertEquals("用户要看仓库更新，先检查工具", reply.reasoning)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("read", reply.toolCalls.single().name)
        assertTrue(deltas.all { !it.content.contains("<th") && !it.content.contains("用户要看仓库") })
        val canonical = LocalCanonicalModelCodec.canonicalizeReply(reply, LocalModelAdapterIds.OPENAI_CHAT, "same")
            .canonicalMessage!!
        val continuation = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical), LocalModelAdapterIds.OPENAI_CHAT, "same",
        ).single()
        assertEquals("<think>用户要看仓库更新，先检查工具</think>正在核查仓库最新提交",
            continuation["content"]!!.jsonPrimitive.content)
        assertFalse(continuation.containsKey("reasoning_content"))
        assertEquals("read", continuation["tool_calls"]!!.jsonArray.single()
            .jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun nonStreamingTaggedContentRetainsNativeReasoningDetailsForReplay() {
        val raw = """{"choices":[{"message":{
            "role":"assistant",
            "content":"<think>检查设置</think>检查完成",
            "reasoning_content":"独立原生思考",
            "reasoning_details":[{"index":0,"id":"sig1","type":"reasoning.text","text":"官方推理"}],
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"read","arguments":"{}"}}]
        },"finish_reason":"tool_calls"}]}"""
        val reply = DeepSeekClient(OkHttpClient(), json).parse(raw, miniMax = true)
        assertEquals("检查完成", reply.content)
        assertTrue(reply.reasoning!!.contains("检查设置"))
        val canonical = LocalCanonicalModelCodec.canonicalizeReply(reply, LocalModelAdapterIds.OPENAI_CHAT, "same")
            .canonicalMessage!!
        val restored = LocalCanonicalModelCodec.message(LocalCanonicalModelCodec.toHistoryMessage(canonical))
        val sameRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(restored), LocalModelAdapterIds.OPENAI_CHAT, "same",
        ).single()
        assertEquals("<think>检查设置</think>检查完成", sameRoute["content"]!!.jsonPrimitive.content)
        assertEquals("独立原生思考", sameRoute["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("sig1", sameRoute["reasoning_details"]!!.jsonArray[0]
            .jsonObject["id"]!!.jsonPrimitive.content)
        val differentRoute = LocalCanonicalModelCodec.toLegacyMessages(
            listOf(canonical), LocalModelAdapterIds.OPENAI_CHAT, "different",
        ).single()
        assertFalse(differentRoute.containsKey("reasoning_details"))
        assertEquals("检查完成", differentRoute["content"]!!.jsonPrimitive.content)
        assertEquals(null, differentRoute["reasoning_content"])
    }
    @Test fun miniMaxChinaCompletion401RetainsBearerAndExplainsAuthentication() = runBlocking {
        for (model in listOf("MiniMax-M3", "MiniMax-M3.1-Flash")) {
            var authorization: String? = null
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                authorization = chain.request().header("Authorization")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401)
                    .message("Unauthorized").body("""{"error":{"message":"login fail: Please carry the API secret key (1004)"}}""".toResponseBody()).build()
            }.build()
            val error = assertThrows(com.labteto.dshmobile.local.LocalModelException::class.java) {
                runBlocking {
                    DeepSeekClient(http, json).completeStreaming(
                        apiKey = "sample-only-key", model = model, messages = emptyList(),
                        tools = JsonArray(emptyList()), baseUrl = "https://api.minimaxi.com/v1",
                    )
                }
            }
            assertEquals("Bearer sample-only-key", authorization)
            assertEquals(401, error.status)
            assertTrue(error.message!!.contains("鉴权失败"))
            assertFalse(error.message!!.contains("sample-only-key"))
        }
    }

}
