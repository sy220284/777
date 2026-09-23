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
    }
}
