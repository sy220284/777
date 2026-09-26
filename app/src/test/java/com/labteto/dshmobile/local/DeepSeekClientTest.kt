package com.labteto.dshmobile.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    private val client = DeepSeekClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

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
    fun officialDeepSeekStreamingUsesAnthropicMessagesEndpoint() = runBlocking {
        val body = listOf(
            "event: message_start",
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}",
            "",
            "event: content_block_start",
            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
            "",
            "event: content_block_delta",
            "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"完成\"}}",
            "",
            "event: message_delta",
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":2}}",
            "",
            "event: message_stop",
            "data: {\"type\":\"message_stop\"}",
        ).joinToString("\n")
        var requestedUrl = ""
        var apiKeyHeader = ""
        var requestBody = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            apiKeyHeader = chain.request().header("x-api-key").orEmpty()
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

        val reply = streamingClient.completeStreaming(
            apiKey = "secret",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            messages = listOf(buildJsonObject { put("role", "user"); put("content", "test") }),
        )

        assertEquals("https://api.deepseek.com/anthropic/v1/messages", requestedUrl)
        assertEquals("secret", apiKeyHeader)
        assertTrue(requestBody.contains("\"max_tokens\":65536"))
        assertTrue(requestBody.contains("\"stream\":true"))
        assertEquals("完成", reply.content)
        assertEquals(3L, reply.usage.promptTokens)
        assertEquals(2L, reply.usage.completionTokens)
    }

}
