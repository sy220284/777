package com.labteto.dshmobile.local.model

import java.io.IOException
import java.net.SocketException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
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
    fun exactReportedCancelWithoutSpaceIsRetryable() {
        val error = client.networkFailure(IOException("stream was reset:CANCEL"))
        assertEquals("MODEL_NETWORK", error.code)
        assertTrue(error.retryable)
        assertFalse(error.message.orEmpty().contains("CANCEL"))
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
        assertFalse("top_p" in payload)

        val input = payload["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input.single().jsonObject["role"]?.jsonPrimitive?.content)
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
