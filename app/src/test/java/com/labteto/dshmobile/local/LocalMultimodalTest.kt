package com.labteto.dshmobile.local

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMultimodalTest {
    @Test
    fun nativeModeMaterializesPixelsWithoutPersistingThem() = runTest {
        val root = createTempDir(prefix = "multimodal-")
        val file = File(root, ".dsh/attachments/abc.png").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val durable = buildLocalUserModelMessage(
            visibleText = "检查图片",
            attachments = listOf(
                LocalImportedAttachment(
                    name = "abc.png",
                    relativePath = file.relativeTo(root).invariantSeparatorsPath,
                    mediaType = "image/png",
                    bytes = file.length(),
                    attachmentId = "abc",
                ),
            ),
        )

        val prepared = prepareLocalMultimodalMessages(
            listOf(durable),
            root,
            LocalImageInputMode.NATIVE,
        )
        val content = prepared.single()["content"] as JsonArray
        val image = content.last().jsonObject

        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertTrue(image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
        assertTrue(durable.toString().contains(LOCAL_IMAGE_REF))
        assertFalse(durable.toString().contains("base64"))
    }

    @Test
    fun toolModeKeepsTextAndDropsNativePixelPart() = runTest {
        val root = createTempDir(prefix = "multimodal-tool-")
        val durable = buildLocalUserModelMessage(
            visibleText = "图片在 .dsh/attachments/a.png",
            attachments = listOf(
                LocalImportedAttachment("a.png", ".dsh/attachments/a.png", "image/png", 4, "a"),
            ),
        )

        val prepared = prepareLocalMultimodalMessages(
            listOf(durable),
            root,
            LocalImageInputMode.TOOL,
        )
        val content = prepared.single()["content"] as JsonArray

        assertEquals(1, content.size)
        assertEquals("text", content.single().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun onlyExplicitImageCapabilityErrorsTriggerAutomaticFallback() {
        assertTrue(
            imageInputUnsupported(
                LocalModelException("MODEL_HTTP_400", "image_url is unsupported by this model", false),
            ),
        )
        assertFalse(
            imageInputUnsupported(
                LocalModelException("MODEL_HTTP_400", "invalid temperature", false),
            ),
        )
        assertFalse(
            imageInputUnsupported(
                LocalModelException("MODEL_HTTP_500", "image service failed", true),
            ),
        )
    }
}
