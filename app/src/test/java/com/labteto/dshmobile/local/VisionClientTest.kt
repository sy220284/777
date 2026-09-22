package com.labteto.dshmobile.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionClientTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = VisionClient(OkHttpClient(), json)

    @Test
    fun payloadUsesOpenAiCompatibleImagePartWithoutLeakingIntoTextHistory() {
        val payload = client.buildPayload(
            model = "vision-model",
            prompt = "找出登录按钮并给出中心坐标",
            imageDataUrl = "data:image/png;base64,AAAA",
        )

        assertEquals("vision-model", payload["model"]?.jsonPrimitive?.content)
        val messages = payload["messages"] as JsonArray
        assertEquals(2, messages.size)
        val user = messages[1].jsonObject
        val content = user["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("找出登录按钮并给出中心坐标", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        val image = content[1].jsonObject
        assertEquals("image_url", image["type"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/png;base64,AAAA",
            image["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun parsesStringContent() {
        val body = """
            {
              "choices": [
                {"message":{"role":"assistant","content":"按钮中心约为 (100, 200)"}}
              ]
            }
        """.trimIndent()

        assertEquals("按钮中心约为 (100, 200)", client.parse(body))
    }

    @Test
    fun parsesArrayTextContent() {
        val body = """
            {
              "choices": [
                {"message":{"role":"assistant","content":[
                  {"type":"text","text":"第一段"},
                  {"type":"output_text","text":"第二段"},
                  {"type":"other","value":"ignore"}
                ]}}
              ]
            }
        """.trimIndent()

        val result = client.parse(body)
        assertEquals("第一段\n第二段", result)
    }

    @Test
    fun emptyVisionContentIsRejected() {
        val body = """
            {"choices":[{"message":{"role":"assistant","content":[]}}]}
        """.trimIndent()

        val result = runCatching { client.parse(body) }
        assertTrue(result.isFailure)
    }
}
