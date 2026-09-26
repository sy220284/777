package com.labteto.dshmobile.local

import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Durable local multimodal vocabulary.
 *
 * Image bytes never enter SessionEventLog/model-history checkpoints. Durable history keeps only
 * lightweight references. At request time, only the newest image-bearing message may reactivate
 * pixels; older images stay addressable through vision_analyze_file without being uploaded again
 * on every model step.
 */
internal const val LOCAL_IMAGE_REF = "local_image_ref"

internal enum class LocalImageCapability {
    UNKNOWN,
    SUPPORTED,
    UNSUPPORTED,
}

internal data class LocalImageRequestBudget(
    val maxImages: Int,
    val maxRawBytes: Long,
) {
    init {
        require(maxImages in 1..20) { "单次图片数量预算必须在 1..20" }
        require(maxRawBytes in 1L..200L * 1024L * 1024L) { "单次图片字节预算无效" }
    }
}

internal fun localImageRequestBudgetForModelConcurrency(maxModelRequests: Int): LocalImageRequestBudget =
    when {
        maxModelRequests <= 2 -> LocalImageRequestBudget(maxImages = 4, maxRawBytes = 16L * 1024L * 1024L)
        maxModelRequests == 3 -> LocalImageRequestBudget(maxImages = 6, maxRawBytes = 24L * 1024L * 1024L)
        else -> LocalImageRequestBudget(maxImages = 8, maxRawBytes = 32L * 1024L * 1024L)
    }

internal data class LocalImageMetadata(
    val mediaType: String,
    val width: Int,
    val height: Int,
)

internal fun validateLocalImageMetadata(metadata: LocalImageMetadata) {
    require(metadata.mediaType in SUPPORTED_LOCAL_IMAGE_TYPES) {
        "仅支持 PNG/JPEG/WebP/GIF 图片"
    }
    require(metadata.width in 1..MAX_LOCAL_IMAGE_EDGE && metadata.height in 1..MAX_LOCAL_IMAGE_EDGE) {
        "图片边长不能超过 $MAX_LOCAL_IMAGE_EDGE 像素"
    }
    require(metadata.width.toLong() * metadata.height.toLong() <= MAX_LOCAL_IMAGE_PIXELS) {
        "图片总像素不能超过 ${MAX_LOCAL_IMAGE_PIXELS / 1_000_000} 百万像素"
    }
}

internal class LocalImageCapabilityRegistry {
    private val states = ConcurrentHashMap<String, LocalImageCapability>()

    fun state(baseUrl: String, model: String): LocalImageCapability =
        states[routeKey(baseUrl, model)] ?: LocalImageCapability.UNKNOWN

    fun markSupported(baseUrl: String, model: String) {
        states[routeKey(baseUrl, model)] = LocalImageCapability.SUPPORTED
    }

    fun markUnsupported(baseUrl: String, model: String) {
        states[routeKey(baseUrl, model)] = LocalImageCapability.UNSUPPORTED
    }

    private fun routeKey(baseUrl: String, model: String): String =
        baseUrl.trim().trimEnd('/').lowercase() + "|" + model.trim().lowercase()
}

internal fun resolveLocalImageInputMode(
    requested: LocalImageInputMode,
    registry: LocalImageCapabilityRegistry,
    baseUrl: String,
    model: String,
): LocalImageInputMode = when (requested) {
    LocalImageInputMode.AUTO -> when (registry.state(baseUrl, model)) {
        LocalImageCapability.UNSUPPORTED -> LocalImageInputMode.TOOL
        LocalImageCapability.UNKNOWN,
        LocalImageCapability.SUPPORTED -> LocalImageInputMode.NATIVE
    }
    else -> requested
}

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
                        image.attachmentId?.let { put("attachmentId", it) }
                        image.width?.let { put("width", it) }
                        image.height?.let { put("height", it) }
                    })
                }
            })
        }
    }
}

internal fun hasLocalImageRefs(messages: List<JsonObject>): Boolean =
    messages.any(::hasLocalImageRefs)

private fun hasLocalImageRefs(message: JsonObject): Boolean =
    (message["content"] as? JsonArray).orEmpty().any { part ->
        (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == LOCAL_IMAGE_REF
    }

internal suspend fun prepareLocalMultimodalMessages(
    messages: List<JsonObject>,
    workspaceRoot: File,
    mode: LocalImageInputMode,
    budget: LocalImageRequestBudget = LocalImageRequestBudget(
        maxImages = 8,
        maxRawBytes = 32L * 1024L * 1024L,
    ),
): List<JsonObject> = withContext(Dispatchers.IO) {
    val activeImageMessageIndices = activeImageMessageIndices(messages, mode)
    val root = workspaceRoot.canonicalFile
    var activeImages = 0
    var activeRawBytes = 0L

    messages.mapIndexed { messageIndex, message ->
        val content = message["content"] as? JsonArray ?: return@mapIndexed message
        val converted = buildJsonArray {
            content.forEach { part ->
                val obj = part as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull != LOCAL_IMAGE_REF) {
                    add(part)
                    return@forEach
                }
                if (mode == LocalImageInputMode.TOOL) return@forEach
                if (messageIndex !in activeImageMessageIndices) {
                    add(historicalImageReference(obj))
                    return@forEach
                }

                val relative = obj["path"]?.jsonPrimitive?.contentOrNull
                    ?: error("图片引用缺少工作区路径")
                val file = File(root, relative).canonicalFile
                require(file.toPath().startsWith(root.toPath())) { "图片引用越过工作区边界" }
                require(file.isFile) { "图片附件不存在：$relative" }
                require(file.length() in 1..MAX_NATIVE_IMAGE_BYTES) {
                    "图片附件超过 ${MAX_NATIVE_IMAGE_BYTES / 1024 / 1024} MB 直传上限：$relative"
                }
                activeImages += 1
                activeRawBytes += file.length()
                require(activeImages <= budget.maxImages) {
                    "本轮直接看图最多 ${budget.maxImages} 张；请减少图片，或改用视觉工具按需分析"
                }
                require(activeRawBytes <= budget.maxRawBytes) {
                    "本轮直接看图原始图片总量超过 ${budget.maxRawBytes / 1024 / 1024} MB；请减少图片，或改用视觉工具按需分析"
                }

                val actualMediaType = sniffLocalImageMediaType(file)
                    ?: error("图片附件格式无法识别：$relative")
                val storedMediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull
                require(storedMediaType == null || storedMediaType == actualMediaType) {
                    "图片附件内容与记录格式不一致：$relative"
                }
                val data = Base64.getEncoder().encodeToString(file.readBytes())
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:$actualMediaType;base64,$data")
                        put("detail", "high")
                    })
                })
            }
        }
        JsonObject(message.toMutableMap().apply { put("content", converted) })
    }
}

private fun activeImageMessageIndices(
    messages: List<JsonObject>,
    mode: LocalImageInputMode,
): Set<Int> {
    if (mode == LocalImageInputMode.TOOL || messages.isEmpty()) return emptySet()
    fun roleAt(index: Int): String? =
        messages[index]["role"]?.jsonPrimitive?.contentOrNull

    if (roleAt(messages.lastIndex) == "user") {
        var start = messages.lastIndex
        while (start > 0 && roleAt(start - 1) == "user") start -= 1
        return (start..messages.lastIndex)
            .filter { index -> hasLocalImageRefs(messages[index]) }
            .toSet()
    }

    val latestUser = messages.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "user"
    }
    return if (latestUser >= 0 && hasLocalImageRefs(messages[latestUser])) {
        setOf(latestUser)
    } else {
        emptySet()
    }
}

private fun historicalImageReference(image: JsonObject): JsonObject {
    val name = image["name"]?.jsonPrimitive?.contentOrNull ?: "未命名图片"
    val path = image["path"]?.jsonPrimitive?.contentOrNull ?: "未知路径"
    val width = image["width"]?.jsonPrimitive?.longOrNull
    val height = image["height"]?.jsonPrimitive?.longOrNull
    val dimensions = if (width != null && height != null) "，尺寸 ${width}×${height}" else ""
    return buildJsonObject {
        put("type", "text")
        put(
            "text",
            "[历史图片引用：$name$dimensions；工作区路径：$path。像素未重复发送；需要重新查看细节时调用 vision_analyze_file。]",
        )
    }
}

internal fun sniffLocalImageMediaType(file: File): String? {
    if (!file.isFile || file.length() < 3L) return null
    val header = ByteArray(12)
    val read = file.inputStream().use { it.read(header) }
    if (read >= 8 &&
        header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
        header[2] == 0x4e.toByte() && header[3] == 0x47.toByte() &&
        header[4] == 0x0d.toByte() && header[5] == 0x0a.toByte() &&
        header[6] == 0x1a.toByte() && header[7] == 0x0a.toByte()
    ) return "image/png"
    if (read >= 3 &&
        header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()
    ) return "image/jpeg"
    if (read >= 6) {
        val gif = String(header, 0, 6, Charsets.US_ASCII)
        if (gif == "GIF87a" || gif == "GIF89a") return "image/gif"
    }
    if (read >= 12 &&
        String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
    ) return "image/webp"
    return null
}

internal fun hasMaterializedImageUrls(messages: List<JsonObject>): Boolean =
    messages.any { message ->
        (message["content"] as? JsonArray).orEmpty().any { part ->
            (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "image_url"
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

internal val SUPPORTED_LOCAL_IMAGE_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
)

internal const val MAX_LOCAL_IMAGE_EDGE = 8192
internal const val MAX_LOCAL_IMAGE_PIXELS = 64_000_000L
private const val MAX_NATIVE_IMAGE_BYTES = 20L * 1024L * 1024L
