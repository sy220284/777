package com.labteto.dshmobile.local.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicMessagesClientTest {
    private val client = AnthropicMessagesClient(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
    )

    @Test
    fun convertsHarnessMessagesImagesAndToolsToNativeMessagesContract() {
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "系统规则")
            },
            buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "看图")
                    })
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/png;base64,AAAA")
                        })
                    })
                })
            },
        )
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "read")
                    put("description", "读取文件")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                    })
                })
            })
        }

        val payload = client.buildPayload("claude-test", messages, tools, 0.4)

        assertEquals("claude-test", payload["model"]?.jsonPrimitive?.content)
        assertEquals("系统规则", payload["system"]?.jsonPrimitive?.content)
        assertEquals("0.4", payload["temperature"]?.jsonPrimitive?.content)
        val userContent = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("text", userContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", userContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        val source = userContent[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("AAAA", source["data"]?.jsonPrimitive?.content)

        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("read", tool["name"]?.jsonPrimitive?.content)
        assertEquals("读取文件", tool["description"]?.jsonPrimitive?.content)
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun convertsToolCallAndToolResultHistoryWithoutLeakingProviderFieldsUpstream() {
        val messages = listOf(
            buildJsonObject {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "toolu_1")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "read")
                            put("arguments", """{"path":"README.md"}""")
                        })
                    })
                })
            },
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", "toolu_1")
                put("content", "hello")
            },
        )

        val payload = client.buildPayload("claude-test", messages, JsonArray(emptyList()), null)
        val converted = payload["messages"]!!.jsonArray
        val assistantBlock = converted[0].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_use", assistantBlock["type"]?.jsonPrimitive?.content)
        assertEquals("README.md", assistantBlock["input"]!!.jsonObject["path"]?.jsonPrimitive?.content)
        val toolResult = converted[1].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", toolResult["type"]?.jsonPrimitive?.content)
        assertEquals("toolu_1", toolResult["tool_use_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun parsesNativeTextReasoningToolCallsAndUsageIntoCompatibilityReply() {
        val reply = client.parse(
            """
            {
              "id":"msg_1",
              "type":"message",
              "role":"assistant",
              "content":[
                {"type":"thinking","thinking":"先读取文件"},
                {"type":"text","text":"我来检查。"},
                {"type":"tool_use","id":"toolu_1","name":"read","input":{"path":"README.md"}}
              ],
              "stop_reason":"tool_use",
              "usage":{"input_tokens":120,"output_tokens":35}
            }
            """.trimIndent(),
        )

        assertEquals("我来检查。", reply.content)
        assertEquals("先读取文件", reply.reasoning)
        assertEquals(1, reply.toolCalls.size)
        assertEquals("toolu_1", reply.toolCalls.single().id)
        assertEquals("read", reply.toolCalls.single().name)
        assertEquals("README.md", reply.toolCalls.single().arguments["path"]?.jsonPrimitive?.content)
        assertEquals(120L, reply.usage.promptTokens)
        assertEquals(35L, reply.usage.completionTokens)
        assertTrue(reply.usage.reported)

        val compatibilityCall = reply.message["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("toolu_1", compatibilityCall["id"]?.jsonPrimitive?.content)
        assertEquals("read", compatibilityCall["function"]!!.jsonObject["name"]?.jsonPrimitive?.content)
    }
}
