package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.TokenPromptBreakdown
import com.labteto.dshmobile.local.chat.chatPostTurnModelMessages
import com.labteto.dshmobile.local.tools.LocalToolCatalog
import java.io.IOException
import java.net.SocketException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesClientTest {
    private val client = OpenAiResponsesClient(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    )

    @Test
    fun apiAndPlanReasoningSelectionPreserveDefaultAndUseNativeFields() {
        listOf(false, true).forEach { plan ->
            listOf<String?>(null, "low", "high").forEach { effort ->
                val payload = client.buildPayload(
                    model = "gpt-6.1-sol", messages = listOf(buildJsonObject {
                        put("role", "user"); put("content", "test")
                    }), tools = JsonArray(emptyList()), temperature = null,
                    reasoningEffort = effort, planSharing = plan,
                )
                if (effort == null) assertFalse(payload.containsKey("reasoning"))
                else assertEquals(effort, payload["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
                assertFalse(payload.containsKey("reasoning_effort"))
                assertEquals("false", payload["store"]!!.jsonPrimitive.content)
                assertEquals("true", payload["stream"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun exactReportedCancelWithoutSpaceIsRetryable() {
        val error = client.networkFailure(IOException("stream was reset:CANCEL"))
        assertEquals("MODEL_NETWORK", error.code)
        assertTrue(error.retryable)
        assertFalse(error.message.orEmpty().contains("CANCEL"))
    }

    @Test
    fun admittedEofAndInStreamFailuresNeverReplayThePlanRequest() = runBlocking {
        val created = """data: {"type":"response.created","response":{"id":"resp-start"}}""" + "\n\n"
        val bodies = listOf(
            created,
            created + """data: {"type":"response.output_text.delta","delta":"partial"}""" + "\n\n",
            created + """data: {"type":"response.failed","response":{"error":{"code":"server_error","message":"failed"}}}""" + "\n\n",
        )
        for (body in bodies) {
            var requests = 0
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").header("x-request-id", "req-start")
                    .body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val failure = try {
                OpenAiResponsesClient(transport, Json).completeStreaming(
                    accessToken = "test", baseUrl = "https://api.openai.com/v1", model = "gpt-test",
                    messages = chatPostTurnModelMessages("test"), tools = JsonArray(emptyList()), planSharing = true,
                )
                error("expected failed stream")
            } catch (error: LocalModelException) { error }
            assertFalse(failure.retryable)
            assertEquals(1, requests)
        }
    }

    @Test
    fun doneOnlyTextCompletesButUsageOnlyTerminalResponseFailsWithoutReplay() = runBlocking {
        for (done in listOf("", """data: {"type":"response.output_text.done","text":"recovered"}""" + "\n\n")) {
            val terminal = """data: {"type":"response.completed","response":{"id":"resp-empty-output","output":[],"usage":{"input_tokens":127941,"output_tokens":270}}}""" + "\n\n"
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body((done + terminal).toResponseBody()).build()
            }.build()
            val result = runCatching {
                OpenAiResponsesClient(http, Json).completeStreaming(
                    "test", "https://api.openai.com/v1", "gpt-test", chatPostTurnModelMessages("test"),
                    JsonArray(emptyList()), planSharing = true,
                )
            }
            if (done.isEmpty()) {
                val error = result.exceptionOrNull() as LocalModelException
                assertEquals("MODEL_EMPTY_RESPONSE", error.code)
                assertFalse(error.retryable)
            } else {
                assertEquals("recovered", result.getOrThrow().content)
                assertEquals(270L, result.getOrThrow().usage.completionTokens)
            }
        }
    }

    @Test
    fun completedEventSettlesBeforeTrailingTransportResetAndKeepsReportedUsage() = runBlocking {
        val terminalFrame =
            """data: {"type":"response.completed","response":{"id":"resp-terminal","output":[{"type":"message","content":[{"type":"output_text","text":"完成"}]}],"usage":{"input_tokens":7,"input_tokens_details":{"cached_tokens":4,"cache_write_tokens":2},"output_tokens":3}}}""" + "\n"
        val bytes = Buffer().writeUtf8(terminalFrame)
        var sourceReads = 0
        var sourceCloses = 0
        val source = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sourceReads += 1
                if (bytes.size > 0L) return bytes.read(sink, byteCount)
                throw IOException("stream was reset: CANCEL")
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {
                sourceCloses += 1
                throw IOException("stream was reset: CANCEL while closing")
            }
        }.buffer()
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/event-stream")
                    .body(object : ResponseBody() {
                        override fun contentType(): MediaType = "text/event-stream".toMediaType()
                        override fun contentLength(): Long = -1L
                        override fun source(): BufferedSource = source
                    })
                    .build()
            }
            .build()
        val terminalClient = OpenAiResponsesClient(
            transport,
            Json { ignoreUnknownKeys = true },
        )

        val reply = terminalClient.completeStreaming(
            accessToken = "test-token",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "测试")
            }),
            tools = JsonArray(emptyList()),
            planSharing = true,
        )

        assertEquals("resp-terminal", reply.requestId)
        assertEquals("完成", reply.content)
        assertTrue(reply.usage.reported)
        assertEquals(7L, reply.usage.promptTokens)
        assertEquals(4L, reply.usage.cacheHitTokens)
        assertEquals(3L, reply.usage.cacheMissTokens)
        assertEquals(2L, reply.usage.cacheWriteTokens)
        assertEquals(3L, reply.usage.completionTokens)
        assertEquals(1, sourceReads)
        assertEquals(1, sourceCloses)
    }

    @Test
    fun legacyToolHistoryKeepsCallsBeforeOutputsWhenSwitchingProtocols() {
        val history = Json.parseToJsonElement("""[
            {"role":"user","content":"读取文件"},
            {"role":"assistant","content":null,"tool_calls":[
                {"id":"call-one","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}},
                {"id":"call-two","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"b.txt\"}"}}
            ]},
            {"role":"tool","tool_call_id":"call-one","content":"A"},
            {"role":"tool","tool_call_id":"call-two","content":"B"}
        ]""").jsonArray.map { it.jsonObject }
        listOf(false, true).forEach { plan ->
            val input = client.buildPayload("gpt-test", history, JsonArray(emptyList()), null,
                planSharing = plan)["input"]!!.jsonArray
            assertEquals(5, input.size)
            assertEquals("function_call", input[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("call-one", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
            assertEquals("{\"path\":\"a.txt\"}", input[1].jsonObject["arguments"]!!.jsonPrimitive.content)
            assertEquals("call-two", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
            assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("call-one", input[3].jsonObject["call_id"]!!.jsonPrimitive.content)
            assertEquals("call-two", input[4].jsonObject["call_id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun nativeResponsesContinuationDoesNotDuplicateLegacyCalls() {
        val message = Json.parseToJsonElement("""{
            "role":"assistant","content":null,
            "tool_calls":[{"id":"call-one","function":{"name":"read_file","arguments":"{}"}}],
            "_dsh_responses_output":[
                {"type":"reasoning","id":"reason-one","encrypted_content":"encrypted"},
                {"type":"function_call","call_id":"call-one","name":"read_file","arguments":"{}"}
            ]
        }""").jsonObject
        val input = client.buildPayload("gpt-test", listOf(message), JsonArray(emptyList()), null)["input"]!!.jsonArray
        assertEquals(message[OpenAiResponsesClient.RESPONSES_OUTPUT_KEY], input)
        assertEquals(2, input.size)
    }

    @Test
    fun chatGptPlanNamespacedFunctionCallRemainsExecutableAndReplayable() {
        val response = Json.parseToJsonElement("""{
            "id":"resp-namespaced-tool",
            "output":[
                {"type":"function_call","namespace":"local","call_id":"call-read","name":"read","arguments":"{\"path\":\"AGENTS.md\"}"}
            ]
        }""").jsonObject
        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
        )

        assertEquals(1, reply.toolCalls.size)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("AGENTS.md", reply.toolCalls.single().arguments["path"]?.jsonPrimitive?.content)

        val toolOutput = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "call-read")
            put("content", "规则内容")
        }
        val input = client.buildPayload(
            model = "gpt-test",
            messages = listOf(reply.message, toolOutput),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = true,
        )["input"]!!.jsonArray

        assertEquals(2, input.size)
        assertEquals("function_call", input[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("local", input[0].jsonObject["namespace"]?.jsonPrimitive?.content)
        assertEquals("read", input[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", input[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call-read", input[1].jsonObject["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun optionalToolArgumentsStayNonStrictUnlessExplicitlyDeclared() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{"name":"read_file","parameters":{
                "type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"}},
                "required":["path"],"additionalProperties":false}}},
            {"type":"function","function":{"name":"strict_tool","strict":true,"parameters":{
                "type":"object","properties":{},"required":[],"additionalProperties":false}}}
        ]""").jsonArray
        listOf(false, true).forEach { plan ->
            val payload = client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = plan)
            val emitted = payload["tools"]!!.jsonArray.let {
                if (plan) it.single().jsonObject["tools"]!!.jsonArray else it
            }
            assertEquals("false", emitted[0].jsonObject["strict"]!!.jsonPrimitive.content)
            assertEquals("true", emitted[1].jsonObject["strict"]!!.jsonPrimitive.content)
            assertEquals(tools[0].jsonObject["function"]!!.jsonObject["parameters"], emitted[0].jsonObject["parameters"])
        }
    }

    @Test
    fun responsesEndpointKeepsPlanSharingOnOpenAiAndHonorsApiKeyBaseUrl() {
        assertEquals("https://api.openai.com/v1/responses", client.responsesEndpoint("https://proxy.example.com/v1", true))
        assertEquals("https://proxy.example.com/v1/responses", client.responsesEndpoint("https://proxy.example.com/v1", false))
        assertEquals("https://proxy.example.com/v1/responses", client.responsesEndpoint("https://proxy.example.com/v1/responses", false))
    }

    @Test
    fun encryptedReasoningIsOnlyForcedForPlanSharingOrOfficialOpenAi() {
        assertTrue(client.shouldIncludeEncryptedReasoning("https://proxy.example.com/v1", true))
        assertTrue(client.shouldIncludeEncryptedReasoning("https://api.openai.com/v1", false))
        assertFalse(client.shouldIncludeEncryptedReasoning("https://proxy.example.com/v1", false))
        val payload = client.buildPayload(
            model = "custom-responses",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "继续") }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = false,
            includeEncryptedReasoning = false,
        )
        assertFalse("include" in payload)
    }

    @Test
    fun refusalContentIsPreservedAsAssistantText() {
        val text = client.responseMessageText(JsonArray(listOf(buildJsonObject {
            put("type", "refusal")
            put("refusal", "无法完成这个请求")
        })))
        assertEquals("无法完成这个请求", text)
    }

    @Test
    fun streamedTextSettlesWhenCompletedResponseOmitsOutputMessage() {
        val response = Json.parseToJsonElement("""{
            "id":"resp-stream-only",
            "output":[],
            "usage":{"input_tokens":4,"output_tokens":6}
        }""").jsonObject

        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
            streamedContent = "这段回复只出现在流式增量里",
        )

        assertEquals("这段回复只出现在流式增量里", reply.content)
        assertEquals(
            "这段回复只出现在流式增量里",
            reply.message["content"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun streamedFallbackRemainsInNextTurnWhenRawOutputHasNoMessageText() {
        val response = Json.parseToJsonElement("""{
            "id":"resp-reasoning-only",
            "output":[
                {"type":"reasoning","id":"reason-1","summary":[],"encrypted_content":"encrypted"}
            ]
        }""").jsonObject
        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
            streamedContent = "上一轮真实回答",
        )
        val nextUser = buildJsonObject {
            put("role", "user")
            put("content", "这是下一轮问题")
        }

        val input = client.buildPayload(
            model = "gpt-test",
            messages = listOf(reply.message, nextUser),
            tools = JsonArray(emptyList()),
            temperature = null,
        )["input"]!!.jsonArray

        assertEquals(3, input.size)
        assertEquals("reasoning", input[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("assistant", input[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("上一轮真实回答", input[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", input[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("这是下一轮问题", input[2].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun standardResponsesStreamErrorsNeverBecomeChatGptPlanErrors() {
        val event = buildJsonObject {
            put("type", "error")
            put("code", "subscription_sharing_usage_limit_exceeded")
            put("message", "limit")
        }
        val standard = client.streamError(event, false, "req-standard", null)
        val plan = client.streamError(event, true, "req-plan", null)
        assertEquals("RESPONSES_STREAM_subscription_sharing_usage_limit_exceeded", standard.code)
        assertEquals("CHATGPT_PLAN_LIMIT_REACHED", plan.code)
    }

    @Test
    fun chatGptPlanPayloadMovesSystemMessagesToInstructionsAndOmitsSamplingControls() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(
                buildJsonObject {
                    put("role", "system")
                    put("content", "保持人物连续性")
                },
                buildJsonObject {
                    put("role", "user")
                    put("content", "继续")
                },
            ),
            tools = JsonArray(emptyList()),
            temperature = 0.9,
        )

        assertEquals("保持人物连续性", payload["instructions"]?.jsonPrimitive?.content)
        assertEquals(false, payload["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, payload["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertFalse("temperature" in payload)
        val unsupported = listOf(
            "background",
            "conversation",
            "max_output_tokens",
            "max_tool_calls",
            "metadata",
            "moderation",
            "multi_agent",
            "prompt",
            "prompt_cache_retention",
            "safety_identifier",
            "temperature",
            "top_logprobs",
            "top_p",
            "truncation",
            "user",
            "previous_response_id",
        )
        unsupported.forEach { field ->
            assertFalse("ChatGPT 套餐请求不得发送 $field", field in payload)
        }

        val input = payload["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input.single().jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun backgroundPlannerMessagesAlwaysProduceRealResponsesInput() {
        for (prompt in listOf("整理这一轮隐藏状态", "根据最近对话生成回复建议，只输出 JSON")) {
            val payload = client.buildPayload(
                model = "gpt-test",
                messages = chatPostTurnModelMessages(prompt),
                tools = JsonArray(emptyList()),
                temperature = null,
                planSharing = true,
            )
            client.validateRequestPayload(payload)

            assertTrue(payload["instructions"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            val input = payload["input"]!!.jsonArray
            assertEquals(1, input.size)
            assertEquals("user", input.single().jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals(prompt, input.single().jsonObject["content"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun systemOnlyResponsesPayloadIsRejectedBeforeNetwork() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "system")
                put("content", "只有 instructions")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = true,
        )
        val error = runCatching { client.validateRequestPayload(payload) }.exceptionOrNull()
            as? LocalModelException
        assertEquals("RESPONSES_INPUT_REQUIRED", error?.code)
        assertFalse(error?.retryable ?: true)
    }

    @Test
    fun admittedPlanStreamInterruptionIsNeverBlindlyReplayed() {
        val error = client.streamInterruptedAfterAdmission(
            planSharing = true,
            requestId = "req-stream-1",
            detail = "流断开",
            cause = IOException("stream was reset: CANCEL"),
        )
        assertEquals("CHATGPT_PLAN_STREAM_INTERRUPTED", error.code)
        assertFalse(error.retryable)
        assertEquals("req-stream-1", error.requestId)
        assertEquals("stream_interrupted_after_admission", error.providerCode)
    }

    @Test
    fun admittedApiKeyResponsesInterruptionIsNeverBlindlyReplayed() {
        val error = client.streamInterruptedAfterAdmission(
            planSharing = false,
            requestId = "req-api-stream-1",
            detail = "流断开",
            cause = IOException("unexpected eof"),
        )
        assertEquals("RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION", error.code)
        assertFalse(error.retryable)
        assertEquals("req-api-stream-1", error.requestId)
        assertEquals("request_interrupted_after_admission", error.providerCode)
    }

    @Test
    fun apiKeyResponsesKeepsSamplingControlOutsidePlanSharing() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "继续")
            }),
            tools = JsonArray(emptyList()),
            temperature = 0.65,
            planSharing = false,
        )

        assertEquals(0.65, payload["temperature"]?.jsonPrimitive?.content?.toDouble())
        assertEquals(false, payload["store"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(true, payload["stream"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun apiKeyResponsesCanUseStablePromptCacheKeyAndThirtyMinuteTtl() {
        val payload = client.buildPayload(
            model = "gpt-5.6",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "继续")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = false,
            promptCacheKey = "stable-session-key",
            promptCacheTtl = "30m",
        )

        assertEquals("stable-session-key", payload["prompt_cache_key"]?.jsonPrimitive?.content)
        val options = payload["prompt_cache_options"]?.jsonObject
            ?: error("missing prompt cache options")
        assertEquals("implicit", options["mode"]?.jsonPrimitive?.content)
        assertEquals("30m", options["ttl"]?.jsonPrimitive?.content)
    }

    @Test
    fun promptCacheOptionsMergeDiagnosticsAndTtlWithoutLeakingPlanOnlyFields() {
        val apiPayload = client.buildPayload(
            model = "gpt-5.6",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "继续")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = false,
            promptCacheComparisonResponseId = "resp-before",
            promptCacheKey = "stable-session-key",
            promptCacheTtl = "30m",
        )
        val apiOptions = apiPayload["prompt_cache_options"]?.jsonObject
            ?: error("missing prompt cache options")
        assertEquals("resp-before", apiOptions["comparison_response_id"]?.jsonPrimitive?.content)
        assertEquals("implicit", apiOptions["mode"]?.jsonPrimitive?.content)
        assertEquals("30m", apiOptions["ttl"]?.jsonPrimitive?.content)

        val planPayload = client.buildPayload(
            model = "gpt-5.6",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "继续")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = true,
            promptCacheKey = "must-not-leak",
            promptCacheTtl = "30m",
        )
        assertFalse("prompt_cache_key" in planPayload)
        assertFalse("prompt_cache_options" in planPayload)
    }

    @Test
    fun planUsageLimitDoesNotClaimTheWholePlanIsEmpty() {
        val error = client.httpError(
            status = 429,
            body = """{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"limit"}}""",
            planSharing = true,
            requestId = "req-plan-limit",
            retryAfterMs = 5_000L,
        )

        assertEquals("CHATGPT_PLAN_LIMIT_REACHED", error.code)
        assertFalse(error.retryable)
        assertTrue(error.message.orEmpty().contains("可能仍有剩余"))
        assertFalse(error.message.orEmpty().contains("已用尽"))
        assertEquals(429, error.status)
        assertEquals(5_000L, error.providerRetryAfterMs)
        assertEquals("req-plan-limit", error.requestId)
        assertEquals("subscription_sharing_usage_limit_exceeded", error.providerCode)
    }

    @Test
    fun planUsageUnavailableKeepsCredentialsAndRetries() {
        val error = client.httpError(
            status = 503,
            body = """{"error":{"code":"subscription_sharing_usage_unavailable","message":"temporarily unavailable"}}""",
            planSharing = true,
            requestId = "req-usage-unavailable",
        )

        assertEquals("CHATGPT_PLAN_USAGE_UNAVAILABLE", error.code)
        assertTrue(error.retryable)
        assertEquals(503, error.status)
        assertEquals("req-usage-unavailable", error.requestId)
    }

    @Test
    fun directAdmissionDetailIsPreservedWithoutCallingItRevokedAuth() {
        val error = client.httpError(
            status = 403,
            body = """{"detail":"serving region is not permitted"}""",
            planSharing = true,
            requestId = "req-admission",
        )

        assertEquals("CHATGPT_PLAN_ADMISSION_403", error.code)
        assertFalse(error.retryable)
        assertEquals("serving region is not permitted", error.message)
        assertEquals("req-admission", error.requestId)
    }

    @Test
    fun connectionAbortGetsFriendlyRetryableMessage() {
        val error = client.networkFailure(SocketException("Software caused connection abort"))

        assertEquals("MODEL_NETWORK", error.code)
        assertTrue(error.retryable)
        assertTrue(error.message.orEmpty().contains("流式连接中断"))
        assertFalse(error.message.orEmpty().contains("Software caused connection abort"))
    }

    @Test
    fun http2StreamResetCancelIsTreatedAsRetryableStreamInterruption() {
        val error = client.networkFailure(IOException("stream was reset: CANCEL"))

        assertEquals("MODEL_NETWORK", error.code)
        assertTrue(error.retryable)
        assertTrue(error.message.orEmpty().contains("流式连接中断"))
        assertFalse(error.message.orEmpty().contains("stream was reset"))
    }

    @Test
    fun retryAfterSecondsAreConvertedToMilliseconds() {
        assertEquals(7_000L, client.parseRetryAfterMillis("7", nowMillis = 0L))
    }

    @Test
    fun multipleSystemMessagesPreserveOrderInInstructions() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(
                buildJsonObject { put("role", "system"); put("content", "规则一") },
                buildJsonObject { put("role", "system"); put("content", "规则二") },
                buildJsonObject { put("role", "user"); put("content", "开始") },
            ),
            tools = JsonArray(emptyList()),
            temperature = null,
        )

        assertEquals("规则一\n\n规则二", payload["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun chatGptPlanWrapsFunctionToolsInNamespace() {
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read_file")
                        put("description", "读取文件")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                },
            ),
        )

        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "读取")
            }),
            tools = tools,
            temperature = null,
            planSharing = true,
        )

        val namespace = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("namespace", namespace["type"]?.jsonPrimitive?.content)
        assertEquals("local", namespace["name"]?.jsonPrimitive?.content)
        val function = namespace["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", function["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", function["name"]?.jsonPrimitive?.content)
        assertTrue(function["parameters"] is JsonObject)
    }

    @Test
    fun chatGptPlanNamespaceCarriesRequiredDescription() {
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read_file")
                        put("description", "读取文件")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                },
            ),
        )

        val payload = client.buildPayload(
            model = "gpt-test",
            messages = emptyList(),
            tools = tools,
            temperature = null,
            planSharing = true,
        )
        val namespace = payload["tools"]!!.jsonArray.single().jsonObject

        assertEquals("namespace", namespace["type"]?.jsonPrimitive?.content)
        assertEquals("local", namespace["name"]?.jsonPrimitive?.content)
        assertTrue(namespace["description"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
        assertTrue(namespace["tools"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun responsesFunctionToolsFillRequiredDescriptionAndParameters() {
        val minimal = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "minimal_tool")
                    })
                },
            ),
        )

        listOf(false, true).forEach { plan ->
            val payload = client.buildPayload(
                model = "gpt-test",
                messages = emptyList(),
                tools = minimal,
                temperature = null,
                planSharing = plan,
            )
            val emitted = payload["tools"]!!.jsonArray.let { outer ->
                if (plan) outer.single().jsonObject["tools"]!!.jsonArray else outer
            }.single().jsonObject

            assertEquals("function", emitted["type"]?.jsonPrimitive?.content)
            assertEquals("minimal_tool", emitted["name"]?.jsonPrimitive?.content)
            assertTrue(emitted["description"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            val parameters = emitted["parameters"]!!.jsonObject
            assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
            assertTrue("properties" in parameters)
        }
    }

    @Test
    fun currentHarnessToolCatalogSatisfiesResponsesRequiredShape() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = emptyList(),
            tools = LocalToolCatalog.specs,
            temperature = null,
            planSharing = true,
        )
        val namespace = payload["tools"]!!.jsonArray.single().jsonObject
        assertTrue(namespace["description"]?.jsonPrimitive?.content.orEmpty().isNotBlank())

        namespace["tools"]!!.jsonArray.forEach { element ->
            val function = element.jsonObject
            assertEquals("function", function["type"]?.jsonPrimitive?.content)
            assertTrue(function["name"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            assertTrue(function["description"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            assertEquals("object", function["parameters"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun invalidResponsesParametersAreRejectedBeforeNetworkRequest() {
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "bad_tool")
                        put("description", "错误工具")
                        put("parameters", "not-a-schema")
                    })
                },
            ),
        )

        val error = runCatching {
            client.buildPayload(
                model = "gpt-test",
                messages = emptyList(),
                tools = tools,
                temperature = null,
                planSharing = true,
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertFalse(error?.retryable ?: true)
        assertTrue(error?.message.orEmpty().contains("parameters"))
    }

    @Test
    fun strictResponsesToolRejectsRootAnyOf() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"root_union",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{},
                    "required":[],
                    "additionalProperties":false,
                    "anyOf":[
                        {"type":"object","properties":{},"required":[],"additionalProperties":false}
                    ]
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("根对象"))
        assertTrue(error?.message.orEmpty().contains("anyOf"))
    }

    @Test
    fun strictResponsesToolAcceptsRequiredNullableOptionalField() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"strict_tool",
                "description":"严格工具",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{
                        "query":{"type":"string"},
                        "limit":{"type":["integer","null"]}
                    },
                    "required":["query","limit"],
                    "additionalProperties":false
                }
            }}
        ]""").jsonArray

        val payload = client.buildPayload(
            model = "gpt-test",
            messages = emptyList(),
            tools = tools,
            temperature = null,
            planSharing = true,
        )
        val function = payload["tools"]!!.jsonArray.single().jsonObject["tools"]!!
            .jsonArray.single().jsonObject

        assertEquals("true", function["strict"]!!.jsonPrimitive.content)
        assertEquals(
            tools.single().jsonObject["function"]!!.jsonObject["parameters"],
            function["parameters"],
        )
    }

    @Test
    fun strictResponsesToolRequiresAdditionalPropertiesFalse() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"strict_tool",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{"query":{"type":"string"}},
                    "required":["query"]
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("additionalProperties=false"))
    }

    @Test
    fun strictResponsesToolRequiresEveryPropertyInRequired() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"strict_tool",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{
                        "query":{"type":"string"},
                        "limit":{"type":"integer"}
                    },
                    "required":["query"],
                    "additionalProperties":false
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("limit"))
        assertTrue(error?.message.orEmpty().contains("required"))
    }

    @Test
    fun strictResponsesToolValidatesNestedObjectsRecursively() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"nested_tool",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{
                        "config":{
                            "type":"object",
                            "properties":{"enabled":{"type":"boolean"}},
                            "required":["enabled"]
                        }
                    },
                    "required":["config"],
                    "additionalProperties":false
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("parameters.properties.config"))
        assertTrue(error?.message.orEmpty().contains("additionalProperties=false"))
    }

    @Test
    fun malformedResponsesPropertiesAreRejectedEvenWhenNonStrict() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"bad_tool",
                "strict":false,
                "parameters":{
                    "type":"object",
                    "properties":"not-an-object"
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("properties"))
    }

    @Test
    fun responsesStrictFlagMustBeJsonBoolean() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"bad_strict",
                "strict":"true",
                "parameters":{"type":"object","properties":{}}
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("strict"))
        assertTrue(error?.message.orEmpty().contains("布尔"))
    }

    @Test
    fun strictResponsesToolRejectsUnsupportedStructuredOutputKeyword() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"unsupported_schema",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{"query":{"type":"string"}},
                    "required":["query"],
                    "additionalProperties":false,
                    "allOf":[{"type":"object"}]
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("allOf"))
    }

    @Test
    fun thirdPartyResponsesDoesNotInheritOpenAiStrictSchemaRules() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"vendor_tool",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{
                        "required_value":{"type":"string"},
                        "optional_value":{"type":"string"}
                    },
                    "required":["required_value"]
                }
            }}
        ]""").jsonArray

        val payload = client.buildPayload(
            model = "vendor-responses-model",
            messages = emptyList(),
            tools = tools,
            temperature = null,
            planSharing = false,
            includeEncryptedReasoning = false,
            enforceOpenAiToolSchema = false,
        )

        val function = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("true", function["strict"]!!.jsonPrimitive.content)
        assertEquals(
            tools.single().jsonObject["function"]!!.jsonObject["parameters"],
            function["parameters"],
        )
    }

    @Test
    fun thirdPartyResponsesStillRejectsMalformedCommonSchemaShapes() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"vendor_bad_tool",
                "strict":false,
                "parameters":{
                    "type":"object",
                    "properties":"not-an-object"
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload(
                model = "vendor-responses-model",
                messages = emptyList(),
                tools = tools,
                temperature = null,
                planSharing = false,
                includeEncryptedReasoning = false,
                enforceOpenAiToolSchema = false,
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("properties"))
    }

    @Test
    fun officialOpenAiApiKeyResponsesEnforcesOpenAiStrictSchemaRules() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"openai_tool",
                "strict":true,
                "parameters":{
                    "type":"object",
                    "properties":{
                        "required_value":{"type":"string"},
                        "optional_value":{"type":"string"}
                    },
                    "required":["required_value"]
                }
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload(
                model = "gpt-test",
                messages = emptyList(),
                tools = tools,
                temperature = null,
                planSharing = false,
                includeEncryptedReasoning = true,
                enforceOpenAiToolSchema = true,
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("additionalProperties=false"))
    }

    @Test
    fun openAiResponsesContractDetectionSeparatesOfficialAndCustomRoutes() {
        assertTrue(client.usesOpenAiResponsesContract("https://api.openai.com/v1", false))
        assertTrue(client.usesOpenAiResponsesContract("https://proxy.example.com/v1", true))
        assertFalse(client.usesOpenAiResponsesContract("https://proxy.example.com/v1", false))
    }

    @Test
    fun namespaceFunctionCallKeepsNamespaceAcrossContinuation() {
        val response = Json.parseToJsonElement("""{
            "id":"resp-namespace-call",
            "output":[
                {
                    "type":"function_call",
                    "id":"fc-1",
                    "call_id":"call-1",
                    "name":"read",
                    "namespace":"local",
                    "arguments":"{\"path\":\"a.txt\"}"
                }
            ]
        }""").jsonObject
        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
        )
        assertEquals(1, reply.toolCalls.size)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("call-1", reply.toolCalls.single().id)

        val rawCall = reply.message[OpenAiResponsesClient.RESPONSES_OUTPUT_KEY]!!
            .jsonArray.single().jsonObject
        assertEquals("local", rawCall["namespace"]?.jsonPrimitive?.content)

        val toolOutput = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", "call-1")
            put("content", "ok")
        }
        val nextInput = client.buildPayload(
            model = "gpt-test",
            messages = listOf(reply.message, toolOutput),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = true,
        )["input"]!!.jsonArray

        assertEquals("function_call", nextInput[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("local", nextInput[0].jsonObject["namespace"]?.jsonPrimitive?.content)
        assertEquals("function_call_output", nextInput[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call-1", nextInput[1].jsonObject["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun responsesRejectsNonStringToolNameAndDescription() {
        val badName = Json.parseToJsonElement("""[
            {"type":"function","function":{"name":123,"parameters":{"type":"object","properties":{}}}}
        ]""").jsonArray
        val badDescription = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"bad_description",
                "description":123,
                "parameters":{"type":"object","properties":{}}
            }}
        ]""").jsonArray

        val nameError = runCatching {
            client.buildPayload("gpt-test", emptyList(), badName, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException
        val descriptionError = runCatching {
            client.buildPayload("gpt-test", emptyList(), badDescription, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", nameError?.code)
        assertTrue(nameError?.message.orEmpty().contains("name"))
        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", descriptionError?.code)
        assertTrue(descriptionError?.message.orEmpty().contains("description"))
    }

    @Test
    fun responsesParametersRootMustBeObjectSchema() {
        val tools = Json.parseToJsonElement("""[
            {"type":"function","function":{
                "name":"bad_root",
                "parameters":{"type":["object","null"],"properties":{}}
            }}
        ]""").jsonArray

        val error = runCatching {
            client.buildPayload("gpt-test", emptyList(), tools, null, planSharing = true)
        }.exceptionOrNull() as? LocalModelException

        assertEquals("RESPONSES_TOOL_SCHEMA_INVALID", error?.code)
        assertTrue(error?.message.orEmpty().contains("根节点"))
    }

    @Test
    fun apiKeyResponsesKeepsFlatFunctionTools() {
        val tools = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read_file")
                        put("description", "读取文件")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                },
            ),
        )

        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "读取")
            }),
            tools = tools,
            temperature = null,
            planSharing = false,
        )

        val function = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", function["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", function["name"]?.jsonPrimitive?.content)
        assertFalse("tools" in function)
        assertTrue(function["parameters"] is JsonObject)
    }

    @Test
    fun imageGenerationResolverRequiresExplicitOfficialSupportedRoute() {
        assertTrue(
            resolveOpenAiImageGenerationToolEnabled(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-6-astra",
                planSharing = false,
                requested = true,
            ),
        )
        assertFalse(
            resolveOpenAiImageGenerationToolEnabled(
                baseUrl = "https://proxy.example.com/v1",
                model = "gpt-6-astra",
                planSharing = false,
                requested = true,
            ),
        )
        assertFalse(
            resolveOpenAiImageGenerationToolEnabled(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-5.6",
                planSharing = false,
                requested = true,
            ),
        )
        assertFalse(
            resolveOpenAiImageGenerationToolEnabled(
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-6-astra",
                planSharing = false,
                requested = false,
            ),
        )
    }

    @Test
    fun nativeImageGenerationToolStaysDisabledWithoutExplicitOptIn() {
        val payload = client.buildPayload(
            model = "gpt-6-astra",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "普通聊天")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = false,
        )

        val tools = payload["tools"] as? JsonArray
        assertTrue(tools == null || tools.none {
            it.jsonObject["type"]?.jsonPrimitive?.content == "image_generation"
        })
    }

    @Test
    fun supportedResponsesModelCanExposeNativeImageGenerationTool() {
        val payload = client.buildPayload(
            model = "gpt-6-astra",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", "画一只猫")
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
            planSharing = false,
            enableImageGenerationTool = true,
        )

        val tools = payload["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("image_generation", tools.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(client.supportsOpenAiImageGenerationTool("gpt-6-astra"))
        assertFalse(client.supportsOpenAiImageGenerationTool("gpt-5.6"))
    }

    @Test
    fun interleavedResponseTextAndGeneratedImageKeepOutputOrder() {
        val response = Json.parseToJsonElement(
            """{
                "id":"resp-interleaved",
                "output":[
                    {"type":"message","content":[{"type":"output_text","text":"第一段"}]},
                    {"type":"image_generation_call","id":"ig_1","status":"completed","result":"AAAA"},
                    {"type":"message","content":[{"type":"output_text","text":"第二段"}]}
                ]
            }""",
        ).jsonObject

        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
        )

        val parts = reply.message["content"]!!.jsonArray
        assertEquals(listOf("output_text", "image_url", "output_text"), parts.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        })
        assertEquals("第一段第二段", reply.content)
    }

    @Test
    fun imageGenerationCallBecomesAssistantImageWithoutPersistingRawResult() {
        val response = Json.parseToJsonElement(
            """{
                "id":"resp-image",
                "output":[{
                    "type":"image_generation_call",
                    "id":"ig_1",
                    "status":"completed",
                    "output_format":"png",
                    "result":"AAAA"
                }]
            }""",
        ).jsonObject

        val reply = client.parseCompleted(
            response = response,
            promptBreakdown = TokenPromptBreakdown(),
        )

        val parts = reply.message["content"]!!.jsonArray
        assertEquals("image_url", parts.single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            parts.single().jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
        val replay = reply.message[OpenAiResponsesClient.RESPONSES_OUTPUT_KEY]!!
            .jsonArray.single().jsonObject
        assertEquals("ig_1", replay["id"]?.jsonPrimitive?.content)
        assertFalse("result" in replay)
    }

    @Test
    fun convertsImageInputForResponses() {
        val payload = client.buildPayload(
            model = "gpt-test",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("content", JsonArray(listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "看图")
                    },
                    buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/png;base64,AAAA")
                        })
                    },
                )))
            }),
            tools = JsonArray(emptyList()),
            temperature = null,
        )

        val content = payload["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            content[1].jsonObject["image_url"]?.jsonPrimitive?.content,
        )
    }
}
