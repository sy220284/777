package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelDelta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    private val client = DeepSeekClient(OkHttpClient(), Json { ignoreUnknownKeys = true })


    @Test
    fun reasoningOverrideMatchesEachProviderWireContract() = runBlocking {
        val requests = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"choices":[{"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}]}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()
        val testClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })
        val history = listOf(buildJsonObject {
            put("role", "user")
            put("content", "test")
        })
        testClient.complete(
            apiKey = "test", baseUrl = "https://api.deepseek.com", model = "deepseek-flash",
            messages = history, tools = JsonArray(emptyList()), reasoningEffort = "none",
        )
        val deepSeekPayload = Json.parseToJsonElement(requests.last()).jsonObject
        assertEquals("disabled", deepSeekPayload["thinking"]?.jsonObject
            ?.get("type")?.jsonPrimitive?.content)
        assertEquals("none", deepSeekPayload["reasoning_effort"]?.jsonPrimitive?.content)

        testClient.complete(
            apiKey = "test", baseUrl = "https://api.openai.com/v1", model = "gpt-5.6-sol",
            messages = history, tools = JsonArray(emptyList()), reasoningEffort = "high",
        )
        val openAiPayload = Json.parseToJsonElement(requests.last()).jsonObject
        assertFalse("OpenAI Chat rejects DeepSeek thinking", openAiPayload.containsKey("thinking"))
        assertEquals("high", openAiPayload["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesToolCallAndReasoning() {
        val reply = client.parse(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "reasoning_content": "先检查目录",
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "list_files", "arguments": "{\"path\":\".\"}"}
                  }]
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("先检查目录", reply.reasoning)
        assertEquals("list_files", reply.toolCalls.single().name)
        assertEquals(".", reply.toolCalls.single().arguments["path"]?.toString()?.trim('"'))
    }

    @Test
    fun streamsTextReasoningAndToolArguments() = runBlocking {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先查\",\"content\":\"好\"}}]}",
            "data: {\"choices\":[{\"delta\":{\"content\":\"的\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":100,\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20,\"completion_tokens\":12}}",
            "data: [DONE]",
        ).joinToString("\n")
        var requestBody = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })
        val deltas = mutableListOf<LocalModelDelta>()

        val reply = streamingClient.completeStreaming(
            apiKey = "test",
            baseUrl = "https://example.com",
            model = "deepseek-chat",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            temperature = 0.85,
            onDelta = { deltas += it },
        )

        assertEquals("好的", reply.content)
        assertEquals("先查", reply.reasoning)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("a.txt", reply.toolCalls.single().arguments["path"]?.toString()?.trim('"'))
        assertTrue(deltas.any { it.content == "好" && it.reasoning == "先查" })
        assertEquals(100L, reply.usage.promptTokens)
        assertEquals(80L, reply.usage.cacheHitTokens)
        assertEquals(20L, reply.usage.cacheMissTokens)
        assertEquals(12L, reply.usage.completionTokens)
        assertTrue(reply.usage.reported)
        assertTrue(requestBody.contains("\"stream_options\":{\"include_usage\":true}"))
        assertTrue(requestBody.contains("\"temperature\":0.85"))
    }

    @Test
    fun capturesUsageBeforeSkippingEmptyChoicesChunk() = runBlocking {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":50,\"prompt_cache_hit_tokens\":10,\"prompt_cache_miss_tokens\":40,\"completion_tokens\":5}}",
            "data: [DONE]",
        ).joinToString("\n")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val reply = streamingClient.completeStreaming(
            apiKey = "test",
            baseUrl = "https://example.com",
            model = "deepseek-chat",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
        )

        assertEquals("完成", reply.content)
        assertEquals(50L, reply.usage.promptTokens)
        assertEquals(10L, reply.usage.cacheHitTokens)
        assertEquals(40L, reply.usage.cacheMissTokens)
        assertEquals(5L, reply.usage.completionTokens)
        assertTrue(reply.usage.reported)
    }

    @Test
    fun parsesDeepSeekUsageAndCacheBreakdown() {
        val reply = client.parse(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "完成"
                }
              }],
              "usage": {
                "prompt_tokens": 1000,
                "prompt_cache_hit_tokens": 800,
                "prompt_cache_miss_tokens": 200,
                "completion_tokens": 120,
                "completion_tokens_details": {
                  "reasoning_tokens": 60
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(1000L, reply.usage.promptTokens)
        assertEquals(800L, reply.usage.cacheHitTokens)
        assertEquals(200L, reply.usage.cacheMissTokens)
        assertEquals(120L, reply.usage.completionTokens)
        assertEquals(60L, reply.usage.reasoningTokens)
        assertEquals(true, reply.usage.reported)
    }

    @Test
    fun normalizesNullAssistantContentForToolReplay() {
        val reply = client.parse(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": null,
                  "reasoning_content": "需要先读取文件",
                  "tool_calls": [{
                    "id": "call-1",
                    "type": "function",
                    "function": {"name": "read", "arguments": "{\"path\":\"a.txt\"}"}
                  }]
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("", reply.message["content"]?.jsonPrimitive?.content)
        assertEquals("需要先读取文件", reply.message["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("read", reply.toolCalls.single().name)
    }

    @Test
    fun omitsToolChoiceForOfficialDeepSeekThinkingModels() {
        assertFalse(shouldSendToolChoice("https://api.deepseek.com", "deepseek-flash"))
        assertFalse(shouldSendToolChoice("https://api.deepseek.com/v1", "deepseek-v4-pro"))
        assertTrue(shouldSendToolChoice("https://api.deepseek.com", "deepseek-chat"))
        assertTrue(shouldSendToolChoice("https://proxy.example.com/v1", "deepseek-flash"))
    }

    @Test
    fun extractsProviderErrorAcrossCompatibleShapes() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            "reasoning_content must be passed back",
            providerErrorDetail("""{"error":{"message":"reasoning_content must be passed back"}}""", json),
        )
        assertEquals(
            "bad request",
            providerErrorDetail("""{"message":"bad request"}""", json),
        )
    }


    @Test
    fun gpt6SolToolCallingUsesNoReasoningInChatCompletions() = runBlocking {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n")
        var requestBody = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        streamingClient.completeStreaming(
            apiKey = "test",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-6-sol",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
        )

        assertTrue(requestBody.contains("\"reasoning_effort\":\"none\""))
        assertTrue(requestBody.contains("\"tool_choice\":\"auto\""))
    }

    @Test
    fun gpt6SolChatSamplingDisablesReasoningEvenWithoutTools() = runBlocking {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]",
        ).joinToString("\n")
        var requestBody = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestBody = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        streamingClient.completeStreaming(
            apiKey = "test",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-6-sol",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            tools = kotlinx.serialization.json.JsonArray(emptyList()),
            temperature = 0.85,
        )

        assertTrue(requestBody.contains("\"reasoning_effort\":\"none\""))
        assertTrue(requestBody.contains("\"temperature\":0.85"))
        assertFalse(requestBody.contains("\"tools\""))
    }

    @Test
    fun gpt6AstraReturnsExplicitUnsupportedErrorForWorkTools() = runBlocking {
        val error = runCatching {
            client.complete(
                apiKey = "test",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-6-astra",
                messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("MODEL_TOOL_CALLING_UNSUPPORTED", error?.code)
        assertTrue(error?.message.orEmpty().contains("Responses API"))
    }


    @Test
    fun truncatedSseAfterAdmissionIsNotBlindlyReplayed() = runBlocking {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            streamingClient.completeStreaming(
                apiKey = "test",
                baseUrl = "https://example.com",
                model = "deepseek-chat",
                messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", error?.code)
        assertFalse(error?.retryable ?: true)
    }

    @Test
    fun malformedSseFrameAfterAdmissionIsNotBlindlyReplayed() = runBlocking {
        val body = "data: {not-json"
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            streamingClient.completeStreaming(
                apiKey = "test",
                baseUrl = "https://example.com",
                model = "deepseek-chat",
                messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("MODEL_STREAM_PROTOCOL", error?.code)
        assertFalse(error?.retryable ?: true)
    }

    @Test
    fun terminalFinishReasonMayCloseStreamWithoutDoneMarker() = runBlocking {
        val body = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
        ).joinToString("\n")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val reply = streamingClient.completeStreaming(
            apiKey = "test",
            baseUrl = "https://example.com",
            model = "deepseek-chat",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
        )

        assertEquals("完成", reply.content)
    }


    @Test
    fun emptySuccessfulResponseAfterAdmissionIsNotBlindlyReplayed() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            streamingClient.completeStreaming(
                apiKey = "test",
                baseUrl = "https://example.com",
                model = "deepseek-chat",
                messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", error?.code)
        assertFalse(error?.retryable ?: true)
    }

    @Test
    fun malformedNonSseFallbackAfterAdmissionIsNotBlindlyReplayed() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{not-json".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val streamingClient = DeepSeekClient(http, Json { ignoreUnknownKeys = true })

        val error = runCatching {
            streamingClient.completeStreaming(
                apiKey = "test",
                baseUrl = "https://example.com",
                model = "deepseek-chat",
                messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
            )
        }.exceptionOrNull() as? LocalModelException

        assertEquals("MODEL_STREAM_PROTOCOL", error?.code)
        assertFalse(error?.retryable ?: true)
    }

}
