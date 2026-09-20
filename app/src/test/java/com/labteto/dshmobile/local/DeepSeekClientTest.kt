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
}
