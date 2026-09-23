package com.labteto.dshmobile.local

import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Durable local multimodal vocabulary.
 *
 * Image bytes never enter SessionEventLog/model-history checkpoints. A user message stores an
 * app-private workspace reference and the model request materializes that reference only for the
 * current HTTP call. This keeps restart/fork/subagent history small while preserving real pixels.
 */
internal const val LOCAL_IMAGE_REF = "local_image_ref"

internal fun buildLocalUserModelMessage(
    visibleText: String,
    attachments: List<LocalImportedAttachment>,
): JsonObject {
    val images = attachments.filter { it.mediaType.startsWith("image/") }
    return buildJsonObject {
        put("role", "user")
        if (images.isEmpty()) {
            put("content", visibleText)
        } else {
            put("content", buildJsonArray {
                if (visibleText.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", visibleText)
                    })
                }
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", LOCAL_IMAGE_REF)
                        put("path", image.relativePath)
                        put("mediaType", image.mediaType)
                        put("name", image.name)
                        put("bytes", image.bytes)
                    })
                }
            })
        }
    }
}

internal fun hasLocalImageRefs(messages: List<JsonObject>): Boolean =
    messages.any { message ->
        (message["content"] as? JsonArray).orEmpty().any { part ->
            (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == LOCAL_IMAGE_REF
        }
    }

internal suspend fun prepareLocalMultimodalMessages(
    messages: List<JsonObject>,
    workspaceRoot: File,
    mode: LocalImageInputMode,
): List<JsonObject> = withContext(Dispatchers.IO) {
    messages.map { message ->
        val content = message["content"] as? JsonArray ?: return@map message
        val converted = buildJsonArray {
            content.forEach { part ->
                val obj = part as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull != LOCAL_IMAGE_REF) {
                    add(part)
                    return@forEach
                }
                if (mode == LocalImageInputMode.TOOL) return@forEach

                val relative = obj["path"]?.jsonPrimitive?.contentOrNull
                    ?: error("图片引用缺少工作区路径")
                val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("image/") }
                    ?: error("图片引用媒体类型无效")
                val root = workspaceRoot.canonicalFile
                val file = File(root, relative).canonicalFile
                require(file.toPath().startsWith(root.toPath())) { "图片引用越过工作区边界" }
                require(file.isFile) { "图片附件不存在：$relative" }
                require(file.length() in 1..MAX_NATIVE_IMAGE_BYTES) {
                    "图片附件超过 ${MAX_NATIVE_IMAGE_BYTES / 1024 / 1024} MB 直传上限：$relative"
                }
                val data = Base64.getEncoder().encodeToString(file.readBytes())
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:$mediaType;base64,$data")
                        put("detail", "high")
                    })
                })
            }
        }
        JsonObject(message.toMutableMap().apply { put("content", converted) })
    }
}

internal fun redactModelImages(messages: List<JsonObject>): List<JsonObject> =
    messages.map { message ->
        val content = message["content"] as? JsonArray ?: return@map message
        val redacted = buildJsonArray {
            content.forEach { part ->
                val obj = part as? JsonObject
                val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
                if (type == "image_url") {
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject { put("url", "[image data omitted]") })
                    })
                } else {
                    add(part)
                }
            }
        }
        JsonObject(message.toMutableMap().apply { put("content", redacted) })
    }

internal fun imageInputUnsupported(error: Throwable): Boolean {
    val modelError = error as? LocalModelException ?: return false
    if (modelError.code !in setOf("MODEL_HTTP_400", "MODEL_HTTP_415", "MODEL_HTTP_422")) return false
    val value = modelError.message.orEmpty().lowercase()
    return listOf("image", "vision", "multimodal", "image_url", "content type", "图片", "视觉", "多模态")
        .any(value::contains)
}

internal fun localImageModeLabel(mode: LocalImageInputMode): String = when (mode) {
    LocalImageInputMode.AUTO -> "自动"
    LocalImageInputMode.NATIVE -> "主模型直读"
    LocalImageInputMode.TOOL -> "视觉工具"
}

private const val MAX_NATIVE_IMAGE_BYTES = 20L * 1024L * 1024L
