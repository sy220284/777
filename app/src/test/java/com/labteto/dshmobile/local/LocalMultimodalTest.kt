package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class LocalMultimodalTest {
    @Test
    fun autoRoutingPrefersNativeVisionThenFallback() {
        assertEquals(
            LocalImageRoute.MAIN_MODEL,
            LocalModelMultimodalCapabilities.route(
                LocalImageInputMode.AUTO,
                "gpt-4o-mini",
                visionConfigured = true,
            ),
        )
        assertEquals(
            LocalImageRoute.VISION_MODEL,
            LocalModelMultimodalCapabilities.route(
                LocalImageInputMode.AUTO,
                "deepseek-chat",
                visionConfigured = true,
            ),
        )
        assertEquals(
            LocalImageRoute.REFERENCE_ONLY,
            LocalModelMultimodalCapabilities.route(
                LocalImageInputMode.AUTO,
                "deepseek-chat",
                visionConfigured = false,
            ),
        )
        assertEquals(
            LocalImageRoute.MAIN_MODEL,
            LocalModelMultimodalCapabilities.route(
                LocalImageInputMode.MAIN_MODEL,
                "unknown-model",
                visionConfigured = false,
            ),
        )
    }

    @Test
    fun durableImageMessageStoresReferenceAndMaterializesOnlyForProvider() {
        val root = createTempDir(prefix = "local-mm-")
        try {
            val image = File(root, ".dsh/attachments/aa/image.png")
            image.parentFile!!.mkdirs()
            image.writeBytes(byteArrayOf(1, 2, 3, 4))
            val attachment = LocalImportedAttachment(
                name = "image.png",
                relativePath = ".dsh/attachments/aa/image.png",
                mediaType = "image/png",
                bytes = image.length(),
                attachmentId = "digest-1",
            )

            val durable = buildLocalMultimodalUserMessage(
                prompt = "看看图片",
                attachments = listOf(attachment),
                visionAnalyses = mapOf("digest-1" to "画面里有一个测试对象"),
            )
            assertTrue(durable.toString().contains("local_image"))
            assertTrue(durable.toString().contains("digest-1"))
            assertFalse(durable.toString().contains("data:image/png;base64"))

            val native = materializeLocalImageMessages(
                messages = listOf(durable),
                workspaceRoot = root,
                nativeImageInput = true,
            ).single()
            val nativeParts = native["content"]!!.jsonArray
            assertEquals("image_url", nativeParts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(
                nativeParts[1].jsonObject["image_url"]!!.jsonObject["url"]!!
                    .jsonPrimitive.content.startsWith("data:image/png;base64,"),
            )

            val fallback = materializeLocalImageMessages(
                messages = listOf(durable),
                workspaceRoot = root,
                nativeImageInput = false,
            ).single()
            val fallbackText = fallback["content"]!!.jsonPrimitive.content
            assertTrue(fallbackText.contains("视觉分析：画面里有一个测试对象"))
            assertTrue(fallbackText.contains("图片附件：image.png"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun modelHistoryReplayRestoresStructuredImageMessage() {
        val durable = buildJsonObject {
            put("role", "user")
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "分析附件")
                        },
                        buildJsonObject {
                            put("type", "local_image")
                            put("attachmentId", "digest-2")
                            put("path", ".dsh/attachments/dd/image.jpg")
                            put("mediaType", "image/jpeg")
                            put("name", "image.jpg")
                            put("bytes", 4)
                        },
                    ),
                ),
            )
        }
        val event = LocalSessionEventLog.Event(
            sequence = 1L,
            type = "user/message",
            createdAt = 1L,
            data = buildJsonObject {
                put("content", "分析附件")
                put("model_message", durable)
            },
        )

        val restored = restoreLocalModelHistory(
            events = listOf(event),
            legacyFallback = emptyList(),
            codec = ModelHistoryCheckpointCodec(),
        )

        assertEquals(1, restored.messages.size)
        assertEquals(durable, restored.messages.single())
    }

    @Test
    fun fileOnlyMessageKeepsLegacyStringContent() {
        val message = buildLocalMultimodalUserMessage(
            prompt = "读取文件",
            attachments = listOf(
                LocalImportedAttachment(
                    name = "notes.txt",
                    relativePath = ".dsh/attachments/notes.txt",
                    mediaType = "text/plain",
                    bytes = 12,
                    attachmentId = "file-digest",
                ),
            ),
        )
        assertTrue(message["content"] is JsonPrimitive)
        assertTrue(message["content"]!!.jsonPrimitive.content.contains("notes.txt"))
    }
}
