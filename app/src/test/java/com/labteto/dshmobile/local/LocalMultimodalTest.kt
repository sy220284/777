package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import com.labteto.dshmobile.local.model.LocalImageCapabilityRegistry
import com.labteto.dshmobile.local.model.LocalImageInputMode
import com.labteto.dshmobile.local.model.LocalImageMetadata
import com.labteto.dshmobile.local.model.LocalImageRequestBudget
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.buildLocalUserModelMessage
import com.labteto.dshmobile.local.model.imageInputUnsupported
import com.labteto.dshmobile.local.model.prepareLocalMultimodalMessages
import com.labteto.dshmobile.local.model.readLocalImageBytesBounded
import com.labteto.dshmobile.local.model.replaceLocalUserModelMessageText
import com.labteto.dshmobile.local.model.resolveLocalImageInputMode
import com.labteto.dshmobile.local.model.validateLocalImageMetadata
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMultimodalTest {
    @Test
    fun providerLimitRejectsOversizedImageBeforeReadingOrEncodingPixels() = runTest {
        val root = java.nio.file.Files.createTempDirectory("provider-image-limit-").toFile()
        try {
            val limit = LocalModelPresets.maxNativeImageBytesFor("MiniMax-M3", "https://api.minimaxi.com/v1")
            val file = File(root, "large.png")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(limit + 1) }
            val message = buildLocalUserModelMessage("image", listOf(
                LocalImportedAttachment("large.png", "large.png", "image/png", limit + 1),
            ))
            val error = runCatching { prepareLocalMultimodalMessages(
                listOf(message), root, LocalImageInputMode.NATIVE, maxImageBytes = limit,
            ) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("直传上限"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun nativeModeMaterializesPixelsWithoutPersistingThem() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-").toFile()
        val file = File(root, ".dsh/attachments/abc.png").apply {
            parentFile?.mkdirs()
            writeBytes(PNG_SIGNATURE + byteArrayOf(1, 2, 3, 4))
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
                    width = 100,
                    height = 80,
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
        assertTrue(durable.toString().contains("\"width\":100"))
    }

    @Test
    fun editedUserTextKeepsDurableImageReferences() {
        val durable = buildLocalUserModelMessage(
            visibleText = "原问题",
            attachments = listOf(
                LocalImportedAttachment(
                    name = "a.png",
                    relativePath = ".dsh/attachments/a.png",
                    mediaType = "image/png",
                    bytes = 12,
                    attachmentId = "a",
                ),
            ),
        )

        val edited = replaceLocalUserModelMessageText(durable, "修改后的问题")
        val content = edited["content"] as JsonArray

        assertEquals("修改后的问题", content.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(LOCAL_IMAGE_REF, content.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(".dsh/attachments/a.png", content.last().jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun oldImagesStayAsReferencesAndOnlyNewestImageMessageReactivatesPixels() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-history-").toFile()
        val first = imageMessage(root, "first.png", "first")
        val second = imageMessage(root, "second.png", "second")

        val prepared = prepareLocalMultimodalMessages(
            listOf(
                first,
                kotlinx.serialization.json.buildJsonObject {
                    put("role", "assistant")
                    put("content", "第一轮已经处理")
                },
                second,
            ),
            root,
            LocalImageInputMode.NATIVE,
        )

        val oldContent = prepared[0]["content"] as JsonArray
        val newContent = prepared[2]["content"] as JsonArray
        assertFalse(oldContent.toString().contains("image_url"))
        assertTrue(oldContent.toString().contains("历史图片引用"))
        assertTrue(oldContent.toString().contains("vision_analyze_file"))
        assertTrue(newContent.toString().contains("image_url"))
    }

    @Test
    fun newTextOnlyUserTurnStopsOlderImagePixelReactivation() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-new-turn-").toFile()
        val image = imageMessage(root, "old.png", "old")
        val laterText = kotlinx.serialization.json.buildJsonObject {
            put("role", "user")
            put("content", "继续讨论，但不要重新上传旧图")
        }

        val prepared = prepareLocalMultimodalMessages(
            listOf(
                image,
                kotlinx.serialization.json.buildJsonObject {
                    put("role", "assistant")
                    put("content", "上一轮图片已经处理")
                },
                laterText,
            ),
            root,
            LocalImageInputMode.NATIVE,
        )

        assertFalse(prepared[0].toString().contains("image_url"))
        assertTrue(prepared[0].toString().contains("历史图片引用"))
    }

    @Test
    fun queuedUserBurstCanActivateImagesBeforeTrailingText() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-queued-").toFile()
        val image = imageMessage(root, "queued.png", "queued")
        val trailingText = kotlinx.serialization.json.buildJsonObject {
            put("role", "user")
            put("content", "这句和刚才图片属于同一批追加消息")
        }

        val prepared = prepareLocalMultimodalMessages(
            listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("role", "assistant")
                    put("content", "处理中")
                },
                image,
                trailingText,
            ),
            root,
            LocalImageInputMode.NATIVE,
        )

        assertTrue(prepared[1].toString().contains("image_url"))
    }

    @Test
    fun nativeRequestBudgetRejectsOversizedActiveImageSetBeforeUnboundedGrowth() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-budget-").toFile()
        val message = imageMessage(root, "budget.png", "budget")

        val failure = runCatching {
            prepareLocalMultimodalMessages(
                listOf(message),
                root,
                LocalImageInputMode.NATIVE,
                LocalImageRequestBudget(maxImages = 1, maxRawBytes = 4),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("原始图片总量"))
    }

    @Test
    fun explicitToolPreferenceIsRespectedWithoutSelectingAnotherModel() {
        val registry = LocalImageCapabilityRegistry()

        assertEquals(
            LocalImageInputMode.TOOL,
            resolveLocalImageInputMode(
                LocalImageInputMode.TOOL,
                registry,
                "https://api.example.com",
                "unknown-model",
            ),
        )
    }

    @Test
    fun capabilityRegistryIsIsolatedByEndpointAndModel() {
        val registry = LocalImageCapabilityRegistry()
        registry.markUnsupported("https://api.example.com", "text-model")
        registry.markSupported("https://api.example.com", "vision-model")

        assertEquals(
            LocalImageInputMode.TOOL,
            resolveLocalImageInputMode(LocalImageInputMode.AUTO, registry, "https://api.example.com", "text-model"),
        )
        assertEquals(
            LocalImageInputMode.NATIVE,
            resolveLocalImageInputMode(LocalImageInputMode.AUTO, registry, "https://api.example.com", "vision-model"),
        )
        assertEquals(
            LocalImageInputMode.NATIVE,
            resolveLocalImageInputMode(LocalImageInputMode.AUTO, registry, "https://other.example.com", "text-model"),
        )
    }

    @Test
    fun imageMetadataBoundsMatchRemoteHarnessSafetyEnvelope() {
        validateLocalImageMetadata(LocalImageMetadata("image/png", 8192, 4096))
        assertTrue(runCatching {
            validateLocalImageMetadata(LocalImageMetadata("image/png", 8193, 1))
        }.isFailure)
        assertTrue(runCatching {
            validateLocalImageMetadata(LocalImageMetadata("image/jpeg", 8000, 8001))
        }.isFailure)
    }

    @Test
    fun toolModeKeepsTextAndDropsNativePixelPart() = runTest {
        val root = kotlin.io.path.createTempDirectory("multimodal-tool-").toFile()
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

    private fun imageMessage(root: File, name: String, id: String) = run {
        val file = File(root, ".dsh/attachments/$name").apply {
            parentFile?.mkdirs()
            writeBytes(PNG_SIGNATURE + ByteArray(8) { 7 })
        }
        buildLocalUserModelMessage(
            visibleText = "附件 $name",
            attachments = listOf(
                LocalImportedAttachment(
                    name = name,
                    relativePath = file.relativeTo(root).invariantSeparatorsPath,
                    mediaType = "image/png",
                    bytes = file.length(),
                    attachmentId = id,
                    width = 320,
                    height = 240,
                ),
            ),
        )
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }

    @Test
    fun boundedImageReadRejectsBytesBeyondFinalStreamLimit() {
        val root = kotlin.io.path.createTempDirectory("multimodal-bounded-read-").toFile()
        try {
            val file = File(root, "image.bin").apply {
                writeBytes(ByteArray(33) { 1 })
            }

            val failure = runCatching {
                readLocalImageBytesBounded(file, 32)
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("直传上限"))
            assertEquals(33L, file.length())
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun clearingRouteForCredentialChangeForgetsStaleImageCapability() {
        val registry = LocalImageCapabilityRegistry()
        registry.markUnsupported("https://api.example.com/", "Vision-Alias")

        assertEquals(
            LocalImageInputMode.TOOL,
            resolveLocalImageInputMode(
                LocalImageInputMode.AUTO,
                registry,
                "https://api.example.com",
                "vision-alias",
            ),
        )

        registry.clearRoute("https://API.EXAMPLE.com", "VISION-ALIAS")

        assertEquals(
            LocalImageInputMode.NATIVE,
            resolveLocalImageInputMode(
                LocalImageInputMode.AUTO,
                registry,
                "https://api.example.com",
                "vision-alias",
            ),
        )
    }

}
