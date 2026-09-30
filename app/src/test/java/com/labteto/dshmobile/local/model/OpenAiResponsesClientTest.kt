package com.labteto.dshmobile.local.model

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
    fun convertsChatCompletionsToolsToResponsesFunctionShape() {
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
        )

        val function = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", function["type"]?.jsonPrimitive?.content)
        assertEquals("read_file", function["name"]?.jsonPrimitive?.content)
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
