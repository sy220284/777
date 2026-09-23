package com.labteto.dshmobile.local

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class LocalImageInputMode {
    AUTO,
    MAIN_MODEL,
    VISION_MODEL;

    companion object {
        fun fromStored(value: String?): LocalImageInputMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class LocalImageRoute {
    MAIN_MODEL,
    VISION_MODEL,
    REFERENCE_ONLY,
}

internal object LocalModelMultimodalCapabilities {
    private val nativeImageHints = listOf(
        "vision",
        "gpt-4o",
        "gpt-4.1",
        "gpt-5",
        "gemini",
        "claude-3",
        "claude-4",
        "sonnet-4",
        "opus-4",
        "haiku-4",
        "qwen-vl",
        "qwen2-vl",
        "qwen2.5-vl",
        "qwen3-vl",
        "llava",
        "pixtral",
        "internvl",
        "deepseek-vl",
    )

    fun supportsImageInput(model: String): Boolean {
        val normalized = model.trim().lowercase()
        if (normalized.isEmpty()) return false
        if (normalized == "deepseek-chat" || normalized == "deepseek-reasoner") return false
        return nativeImageHints.any(normalized::contains) ||
            Regex("(^|[-_/])vl([-. _/]|$)").containsMatchIn(normalized)
    }

    fun route(
        mode: LocalImageInputMode,
        model: String,
        visionConfigured: Boolean,
    ): LocalImageRoute = when (mode) {
        LocalImageInputMode.MAIN_MODEL -> LocalImageRoute.MAIN_MODEL
        LocalImageInputMode.VISION_MODEL ->
            if (visionConfigured) LocalImageRoute.VISION_MODEL else LocalImageRoute.REFERENCE_ONLY
        LocalImageInputMode.AUTO -> when {
            supportsImageInput(model) -> LocalImageRoute.MAIN_MODEL
            visionConfigured -> LocalImageRoute.VISION_MODEL
            else -> LocalImageRoute.REFERENCE_ONLY
        }
    }
}

/**
 * Builds one durable user message. Image pixels never enter the session log: only a workspace-
 * confined reference is stored and later materialized for a provider request.
 */
internal fun buildLocalMultimodalUserMessage(
    prompt: String,
    attachments: List<LocalImportedAttachment>,
    visionAnalyses: Map<String, String> = emptyMap(),
): JsonObject {
    val images = attachments.filter { it.mediaType.startsWith("image/") }
    val files = attachments.filterNot { it.mediaType.startsWith("image/") }
    val text = buildString {
        if (prompt.isNotBlank()) append(prompt.trim())
        if (attachments.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("本次附件已导入本机工作区：")
            attachments.forEach { attachment ->
                val kind = if (attachment.mediaType.startsWith("image/")) "图片" else "文件"
                append("\n- ").append(kind).append("：").append(attachment.name)
                    .append(" → ").append(attachment.relativePath)
                    .append("（").append(attachment.bytes).append(" B）")
                visionAnalyses[attachment.attachmentId]?.takeIf(String::isNotBlank)?.let { analysis ->
                    append("\n  视觉分析：").append(analysis.trim())
                }
            }
        }
        if (isBlank() && images.isNotEmpty()) append("请分析所附图片。")
        if (files.isNotEmpty() && images.isEmpty() && prompt.isBlank()) append("请处理上述附件。")
    }

    if (images.isEmpty()) {
        return buildJsonObject {
            put("role", "user")
            put("content", text)
        }
    }
    val parts = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
        images.forEach { attachment ->
            add(buildJsonObject {
                put("type", LOCAL_IMAGE_TYPE)
                put("attachmentId", attachment.attachmentId)
                put("path", attachment.relativePath)
                put("mediaType", attachment.mediaType)
                put("name", attachment.name)
                put("bytes", attachment.bytes)
            })
        }
    }
    return buildJsonObject {
        put("role", "user")
        put("content", parts)
    }
}

internal fun materializeLocalImageMessages(
    messages: List<JsonObject>,
    workspaceRoot: File,
    nativeImageInput: Boolean,
): List<JsonObject> = messages.map { message ->
    val content = message["content"] as? JsonArray ?: return@map message
    if (content.none { part -> (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == LOCAL_IMAGE_TYPE }) {
        return@map message
    }

    if (!nativeImageInput) {
        val text = content.joinToString("\n") { part ->
            val objectPart = part as? JsonObject ?: return@joinToString ""
            when (objectPart["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> objectPart["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                LOCAL_IMAGE_TYPE -> {
                    val path = objectPart["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = objectPart["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
                    "[图片附件：" + name + "，路径 " + path + "]"
                }
                else -> ""
            }
        }.trim()
        return@map JsonObject(message.toMutableMap().apply { put("content", JsonPrimitive(text)) })
    }

    val materialized = buildJsonArray {
        content.forEach { part ->
            val objectPart = part as? JsonObject
            if (objectPart?.get("type")?.jsonPrimitive?.contentOrNull != LOCAL_IMAGE_TYPE) {
                add(part)
                return@forEach
            }
            val path = objectPart["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val name = objectPart["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mediaType = objectPart["mediaType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val file = resolveWorkspaceFile(workspaceRoot, path)
            require(file.length() in 1..MAX_NATIVE_IMAGE_BYTES) {
                "图片 " + name + " 超过主模型图片输入上限"
            }
            require(mediaType.startsWith("image/")) { "图片 " + name + " 的媒体类型无效" }
            val dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(file.readBytes())
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", dataUrl)
                    put("detail", "auto")
                })
            })
        }
    }
    JsonObject(message.toMutableMap().apply { put("content", materialized) })
}

internal fun localImageDataUrl(
    workspaceRoot: File,
    attachment: LocalImportedAttachment,
): String {
    require(attachment.mediaType.startsWith("image/")) { "附件不是图片：" + attachment.name }
    val file = resolveWorkspaceFile(workspaceRoot, attachment.relativePath)
    require(file.length() in 1..MAX_NATIVE_IMAGE_BYTES) {
        "图片 " + attachment.name + " 超过图片输入上限"
    }
    return "data:" + attachment.mediaType + ";base64," + Base64.getEncoder().encodeToString(file.readBytes())
}

internal fun normalizeLocalAttachmentMediaType(declared: String?, displayName: String): String {
    val normalized = declared?.trim()?.lowercase().orEmpty()
    if (normalized == "image/jpg" || normalized == "image/pjpeg") return "image/jpeg"
    if (normalized.isNotEmpty() && normalized != "application/octet-stream") return normalized
    return when (displayName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> normalized.ifBlank { "application/octet-stream" }
    }
}

internal fun finalizeLocalImportedAttachment(
    tempFile: File,
    workspaceRoot: File,
    attachmentsRoot: File,
    displayName: String,
    mediaType: String,
): LocalImportedAttachment {
    val digest = sha256Hex(tempFile)
    val canonicalMediaType = normalizeLocalAttachmentMediaType(mediaType, displayName)
    val originalSuffix = displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        ?.let { "." + it }
        .orEmpty()
    val suffix = when (canonicalMediaType) {
        "image/png" -> ".png"
        "image/jpeg" -> ".jpg"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        else -> originalSuffix
    }
    val shard = File(attachmentsRoot, digest.take(2)).apply { mkdirs() }
    val target = File(shard, digest + suffix)
    if (target.exists()) {
        tempFile.delete()
    } else if (!tempFile.renameTo(target)) {
        tempFile.copyTo(target, overwrite = false)
        tempFile.delete()
    }
    return LocalImportedAttachment(
        attachmentId = digest,
        name = displayName,
        relativePath = target.relativeTo(workspaceRoot).invariantSeparatorsPath,
        mediaType = canonicalMediaType,
        bytes = target.length(),
    )
}

private fun resolveWorkspaceFile(workspaceRoot: File, relativePath: String): File {
    require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "附件路径必须是工作区相对路径" }
    val root = workspaceRoot.canonicalFile
    val file = File(root, relativePath).canonicalFile
    require(file.toPath().startsWith(root.toPath())) { "拒绝读取工作区之外的附件" }
    require(file.isFile) { "附件不存在：" + relativePath }
    return file
}

private fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val LOCAL_IMAGE_TYPE = "local_image"
private const val MAX_NATIVE_IMAGE_BYTES = 20L * 1024L * 1024L
