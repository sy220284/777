package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentModelProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekAnthropicCodecTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun officialEndpointSelectsMessagesAndConvertsTools() {
        assertEquals(
            AgentModelProtocol.ANTHROPIC_MESSAGES,
            transportProtocolFor("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            deepSeekAnthropicEndpoint("https://api.deepseek.com"),
        )

        val payload = deepSeekAnthropicPayload(
            model = "deepseek-flash",
            messages = listOf(
                buildJsonObject { put("role", "system"); put("content", "system") },
                buildJsonObject { put("role", "user"); put("content", "hello") },
            ),
            tools = buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read")
                        put("description", "read file")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                })
            },
            stream = true,
        )

        assertEquals("system", payload["system"]?.jsonPrimitive?.content)
        assertTrue(payload["stream"]?.jsonPrimitive?.content == "true")
        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("read", tool["name"]?.jsonPrimitive?.content)
        assertEquals("object", tool["input_schema"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun openAiToolHistoryBecomesToolUseAndToolResult() {
        val messages = listOf(
            buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "c1")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "read")
                            put("arguments", "{\"path\":\"a.txt\"}")
                        })
                    })
                })
            },
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "c1")
                put("content", "ok")
            },
        )
        val payload = deepSeekAnthropicPayload("m", messages, JsonArray(emptyList()), false)
        val encoded = payload["messages"]!!.toString()

        assertTrue(encoded.contains("\"type\":\"tool_use\""))
        assertTrue(encoded.contains("\"type\":\"tool_result\""))
        assertTrue(encoded.contains("\"tool_use_id\":\"c1\""))
    }

    @Test
    fun parsesMessagesResponseBackIntoStableLocalShape() {
        val root = json.parseToJsonElement(
            """
            {
              "content": [
                {"type":"thinking","thinking":"先看文件"},
                {"type":"text","text":"正在处理"},
                {"type":"tool_use","id":"c1","name":"read","input":{"path":"a.txt"}}
              ],
              "usage":{"input_tokens":100,"cache_read_input_tokens":60,"output_tokens":20}
            }
            """.trimIndent(),
        ).jsonObject

        val reply = parseDeepSeekAnthropicReply(root)

        assertEquals("正在处理", reply.content)
        assertEquals("先看文件", reply.reasoning)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("a.txt", reply.toolCalls.single().arguments["path"]?.jsonPrimitive?.content)
        assertEquals(160L, reply.usage.promptTokens)
        assertEquals(60L, reply.usage.cacheHitTokens)
        assertEquals(20L, reply.usage.completionTokens)
    }

    @Test
    fun streamingAccumulatorReassemblesTextThinkingAndToolJson() {
        val deltas = mutableListOf<LocalModelDelta>()
        val accumulator = DeepSeekAnthropicStreamAccumulator(json) { deltas += it }
        listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"想"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"好"}}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"c1","name":"read","input":{}}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""",
            """{"type":"message_delta","usage":{"output_tokens":7}}""",
        ).forEach { raw ->
            accumulator.accept(json.parseToJsonElement(raw).jsonObject)
        }

        val reply = accumulator.result()
        assertEquals("好", reply.content)
        assertEquals("想", reply.reasoning)
        assertEquals("read", reply.toolCalls.single().name)
        assertTrue(deltas.any { it.content == "好" })
        assertTrue(deltas.any { it.reasoning == "想" })
        assertEquals(7L, reply.usage.completionTokens)
    }
}
