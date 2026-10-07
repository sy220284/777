package com.labteto.dshmobile.local.chat

import android.graphics.BitmapFactory
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.LocalMessageMediaPolicy
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import com.labteto.dshmobile.local.model.LocalCanonicalContent
import com.labteto.dshmobile.local.model.LocalImageMetadata
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.OpenAiResponsesClient
import com.labteto.dshmobile.local.model.sniffLocalImageMediaType
import com.labteto.dshmobile.local.model.validateLocalImageMetadata
import com.labteto.dshmobile.local.session.LocalMessageBlock
import com.labteto.dshmobile.local.session.LocalMessageMediaSource
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalMaterializedChatReply(
    val reply: LocalModelReply,
    val blocks: List<LocalMessageBlock>,
)

/**
 * Converts provider image payloads into durable local attachments before transcript/history commit.
 *
 * The returned reply replaces image data URLs with LOCAL_IMAGE_REF objects, so SessionEventLog and
 * model-history checkpoints never retain generated base64 pixels.
 */
internal suspend fun materializeLocalChatReplyMedia(
    reply: LocalModelReply,
    workspaceRoot: File,
): LocalMaterializedChatReply = withContext(Dispatchers.IO) {
    val canonical = reply.canonicalMessage
    val images = canonical?.content
        ?.filterIsInstance<LocalCanonicalContent.Image>()
        .orEmpty()
    if (images.isEmpty()) {
        return@withContext LocalMaterializedChatReply(
            reply = reply,
            blocks = reply.content
                ?.takeIf(String::isNotBlank)
                ?.let { listOf(LocalMessageBlock.Text(it)) }
                .orEmpty(),
        )
    }

    val attachments = images.mapIndexed { index, image ->
        materializeGeneratedImage(
            workspaceRoot = workspaceRoot,
            dataUrl = image.dataUrl,
            ordinal = index + 1,
        )
    }
    val canonicalText = canonical?.content
        ?.filterIsInstance<LocalCanonicalContent.Text>()
        ?.joinToString("") { it.text }
        .orEmpty()
    val finalText = reply.content.orEmpty()
    val textChanged = finalText != canonicalText
    var textInserted = false
    var imageIndex = 0
    val blocks = buildList {
        canonical?.content.orEmpty().forEach { content ->
            when (content) {
                is LocalCanonicalContent.Text -> {
                    if (textChanged) {
                        if (!textInserted && finalText.isNotBlank()) {
                            add(LocalMessageBlock.Text(finalText))
                            textInserted = true
                        }
                    } else if (content.text.isNotBlank()) {
                        add(LocalMessageBlock.Text(content.text))
                    }
                }
                is LocalCanonicalContent.Image -> {
                    val attachment = attachments[imageIndex++]
                    add(attachment.toMessageImageBlock())
                }
                else -> Unit
            }
        }
        if (!textInserted && textChanged && finalText.isNotBlank()) {
            add(0, LocalMessageBlock.Text(finalText))
        }
    }

    val materializedMessage = replaceAssistantImageDataWithLocalRefs(reply.message, attachments)
    val durableMessage = if (textChanged) {
        JsonObject(materializedMessage - OpenAiResponsesClient.RESPONSES_OUTPUT_KEY)
    } else {
        materializedMessage
    }

    LocalMaterializedChatReply(
        reply = reply.copy(message = durableMessage),
        blocks = blocks,
    )
}

private fun LocalImportedAttachment.toMessageImageBlock(): LocalMessageBlock.Image =
    LocalMessageBlock.Image(
        relativePath = relativePath,
        mediaType = mediaType,
        name = name,
        bytes = bytes,
        attachmentId = attachmentId,
        width = width,
        height = height,
        source = LocalMessageMediaSource.MODEL,
    )

private fun materializeGeneratedImage(
    workspaceRoot: File,
    dataUrl: String,
    ordinal: Int,
): LocalImportedAttachment {
    val comma = dataUrl.indexOf(',')
    require(dataUrl.startsWith("data:image/") && comma > 5) {
        "模型图片必须是受支持的 data URL"
    }
    val metadata = dataUrl.substring(5, comma)
    require(metadata.endsWith(";base64")) { "模型图片 data URL 必须使用 base64 编码" }
    val declaredMediaType = metadata.substringBefore(';').lowercase()
    val encoded = dataUrl.substring(comma + 1)
    require(encoded.length <= LocalMessageMediaPolicy.MAX_GENERATED_IMAGE_BASE64_CHARS) {
        "模型图片编码体积超过本地安全上限"
    }
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }
        .getOrElse { throw IllegalArgumentException("模型图片 base64 无法解码", it) }
    require(bytes.isNotEmpty() && bytes.size <= LocalMessageMediaPolicy.MAX_GENERATED_IMAGE_BYTES) {
        "模型图片超过本地 ${LocalMessageMediaPolicy.MAX_GENERATED_IMAGE_BYTES / 1024 / 1024} MB 安全上限"
    }

    val dir = File(workspaceRoot, ".dsh/attachments").apply { mkdirs() }
    val incoming = File(dir, ".generated-${UUID.randomUUID()}")
    incoming.writeBytes(bytes)
    try {
        val actualMediaType = sniffLocalImageMediaType(incoming)
            ?: error("模型返回的图片格式无法识别")
        require(actualMediaType == declaredMediaType) {
            "模型图片声明格式与实际内容不一致"
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(incoming.absolutePath, options)
        val imageMetadata = LocalImageMetadata(
            mediaType = actualMediaType,
            width = options.outWidth,
            height = options.outHeight,
        )
        validateLocalImageMetadata(imageMetadata)

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val extension = when (actualMediaType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> error("不支持的模型图片格式：$actualMediaType")
        }
        val target = File(dir, "$digest.$extension")
        if (target.exists()) {
            incoming.delete()
        } else if (!incoming.renameTo(target)) {
            incoming.copyTo(target, overwrite = false)
            incoming.delete()
        }

        return LocalImportedAttachment(
            name = "模型图片-$ordinal-${digest.take(8)}.$extension",
            relativePath = target.relativeTo(workspaceRoot).invariantSeparatorsPath,
            mediaType = actualMediaType,
            bytes = target.length(),
            attachmentId = digest,
            width = imageMetadata.width,
            height = imageMetadata.height,
        )
    } catch (error: Throwable) {
        incoming.delete()
        throw error
    }
}

private fun replaceAssistantImageDataWithLocalRefs(
    message: JsonObject,
    attachments: List<LocalImportedAttachment>,
): JsonObject {
    val content = message["content"] as? JsonArray ?: return message
    var imageIndex = 0
    val replaced = buildJsonArray {
        content.forEach { part ->
            val obj = part as? JsonObject
            val kind = obj?.get("type")?.jsonPrimitive?.contentOrNull
            if (kind in setOf("image_url", "input_image") && imageIndex < attachments.size) {
                val attachment = attachments[imageIndex++]
                add(buildJsonObject {
                    put("type", LOCAL_IMAGE_REF)
                    put("path", attachment.relativePath)
                    put("mediaType", attachment.mediaType)
                    put("name", attachment.name)
                    put("bytes", attachment.bytes)
                    attachment.attachmentId?.let { put("attachmentId", it) }
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    put("source", "model")
                })
            } else {
                add(part)
            }
        }
    }
    return JsonObject(message.toMutableMap().apply { put("content", replaced) })
}
