package com.labteto.dshmobile.local

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
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
}
