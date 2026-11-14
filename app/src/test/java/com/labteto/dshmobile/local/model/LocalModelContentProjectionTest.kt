package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelFailureOrigin
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalModelContentProjectionTest {
    private val client = AnthropicMessagesClient(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun route(protocol: LocalModelProtocol = LocalModelProtocol.ANTHROPIC_MESSAGES) =
        LocalResolvedModelRoute(
            profileId = "route", provider = "test", model = "model",
            baseUrl = "https://example.test/v1", authKind = LocalModelAuthKind.API_KEY,
            protocol = protocol, bearerToken = "test-key",
            capabilities = LocalModelRuntimeCapabilities(imageInput = true),
        )

    private fun user(vararg blocks: kotlinx.serialization.json.JsonObject) = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray { blocks.forEach(::add) })
    }

    private fun rawFile(filename: String, body: String) = buildJsonObject {
        put("type", "input_file")
        put("filename", filename)
        put("file_data", body)
    }

    @Test
    fun switchingToAnthropicAutomaticallyConvertsCurrentSmallTextAttachment() {
        val encoded = Base64.getEncoder().encodeToString("需求：完成所有功能测试".toByteArray())
        val messages = listOf(user(rawFile("requirements.txt", "data:text/plain;base64,$encoded")))
        val prepared = prepareLocalModelAdapterRequest(
            route(), messages, JsonArray(emptyList()), temperature = null, streaming = true,
        )
        val text = prepared.request.messages.single().content.single() as LocalCanonicalContent.Text
        assertTrue(text.text.contains("需求：完成所有功能测试"))
        assertTrue(text.text.contains("requirements.txt"))
        assertFalse(text.text.contains(encoded))
        assertTrue(messages.single().toString().contains(encoded)) // durable caller input untouched

        val payload = client.buildPayload(route(), prepared.request.messages, emptyList(), null)
        val block = payload.getValue("messages").jsonArray.single().jsonObject
            .getValue("content").jsonArray.single().jsonObject
        assertEquals("text", block.getValue("type").jsonPrimitive.content)
        assertTrue(block.getValue("text").jsonPrimitive.content.contains("完成所有功能测试"))
    }

    @Test
    fun switchingProviderKeepsHistoricalAttachmentReferencesWithoutResendingBytes() {
        val history = listOf(
            user(rawFile("旧资料.pdf", "data:application/pdf;base64,SECRET-NEVER-SEND")),
            user(buildJsonObject {
                put("type", "video_url")
                put("video_url", buildJsonObject { put("url", "data:video/mp4;base64,PRIVATE-VIDEO") })
            }),
            buildJsonObject { put("role", "assistant"); put("content", "之前已浏览附件") },
            buildJsonObject { put("role", "user"); put("content", "继续总结项目") },
        )
        val prepared = prepareLocalModelAdapterRequest(
            route(), history, JsonArray(emptyList()), temperature = null, streaming = true,
        )
        val first = (prepared.request.messages[0].content.single() as LocalCanonicalContent.Text).text
        val second = (prepared.request.messages[1].content.single() as LocalCanonicalContent.Text).text
        assertTrue(first.contains("旧资料.pdf"))
        assertTrue(first.contains("重新附加"))
        assertTrue(second.contains("video_url"))
        assertFalse((first + second).contains("SECRET-NEVER-SEND"))
        assertFalse((first + second).contains("PRIVATE-VIDEO"))
        val payload = client.buildPayload(route(), prepared.request.messages, emptyList(), null)
        assertFalse(payload.toString().contains("SECRET-NEVER-SEND"))
        assertFalse(payload.toString().contains("PRIVATE-VIDEO"))
        assertEquals("input_file", history[0].getValue("content").jsonArray[0].jsonObject
            .getValue("type").jsonPrimitive.content)
    }

    @Test
    fun currentBinaryFileAndMalformedInlineTextRemainExplicitPreflightErrors() {
        val inputs = listOf(
            rawFile("test.pdf", "data:application/pdf;base64,UEsDBA=="),
            rawFile("broken.txt", "data:text/plain;base64,bad!!!"),
            rawFile("huge.txt", "data:text/plain;base64," + "AAAA".repeat(12_000)),
        )
        inputs.forEach { content ->
            val prepared = prepareLocalModelAdapterRequest(
                route(), listOf(user(content)), JsonArray(emptyList()), null, true,
            )
            assertTrue(prepared.request.messages.single().content.single() is LocalCanonicalContent.Raw)
            val error = assertThrows(LocalModelException::class.java) {
                client.buildPayload(route(), prepared.request.messages, emptyList(), null)
            }
            assertEquals("MODEL_PROTOCOL_INPUT_UNSUPPORTED", error.code)
            assertEquals(LocalModelFailureOrigin.PROTOCOL_ENCODE, error.origin)
        }
    }

    @Test
    fun projectionDoesNotChangeResponsesHistoryOrToolMessages() {
        val original = listOf(
            LocalCanonicalMessage(LocalCanonicalRole.USER, listOf(
                LocalCanonicalContent.Raw(rawFile("file.pdf", "data:application/pdf;base64,AAAA")),
            )),
            LocalCanonicalMessage(LocalCanonicalRole.TOOL, listOf(
                LocalCanonicalContent.ToolResult("call-1", "ok"),
            )),
        )
        assertEquals(original, projectCanonicalHistoryForProtocol(original, LocalModelProtocol.RESPONSES))
        val anthropic = projectCanonicalHistoryForProtocol(original, LocalModelProtocol.ANTHROPIC_MESSAGES)
        assertEquals(original[1], anthropic[1])
        assertTrue(anthropic[0].content.single() is LocalCanonicalContent.Raw)
    }
}
