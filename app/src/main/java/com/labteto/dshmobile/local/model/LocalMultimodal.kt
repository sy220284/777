package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.attachment.LocalDocumentContent
import com.labteto.dshmobile.local.attachment.LocalImportedAttachment
import com.labteto.dshmobile.local.attachment.renderLocalPdfForModel
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
internal const val LOCAL_FILE_REF = "local_file_ref"

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
        states[routeKey(baseUrl, model)] ?: when (
            LocalModelPresets.documentedImageInputSupport(model, baseUrl)
        ) {
            true -> LocalImageCapability.SUPPORTED
            false -> LocalImageCapability.UNSUPPORTED
            null -> LocalImageCapability.UNKNOWN
        }

    fun markSupported(baseUrl: String, model: String) {
        states[routeKey(baseUrl, model)] = LocalImageCapability.SUPPORTED
    }

    fun markUnsupported(baseUrl: String, model: String) {
        states[routeKey(baseUrl, model)] = LocalImageCapability.UNSUPPORTED
    }

    fun clearRoute(baseUrl: String, model: String) {
        states.remove(routeKey(baseUrl, model))
    }

    private fun routeKey(baseUrl: String, model: String): String =
        baseUrl.trim().trimEnd('/').lowercase() + "|" + model.trim().lowercase()
}

internal fun resolveLocalImageInputMode(
    requested: LocalImageInputMode,
    registry: LocalImageCapabilityRegistry,
    baseUrl: String,
    model: String,
): LocalImageInputMode = when (registry.state(baseUrl, model)) {
    LocalImageCapability.UNSUPPORTED -> LocalImageInputMode.TOOL
    LocalImageCapability.UNKNOWN,
    LocalImageCapability.SUPPORTED -> if (requested == LocalImageInputMode.TOOL) LocalImageInputMode.TOOL else LocalImageInputMode.NATIVE
}

internal fun buildLocalUserModelMessage(
    visibleText: String,
    attachments: List<LocalImportedAttachment>,
): JsonObject = buildJsonObject {
    put("role", "user")
    if (attachments.isEmpty()) {
        put("content", visibleText)
    } else {
        put("content", buildJsonArray {
            if (visibleText.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", visibleText)
                })
            }
            attachments.forEach { attachment ->
                add(buildJsonObject {
                    put(
                        "type",
                        if (attachment.mediaType.startsWith("image/")) LOCAL_IMAGE_REF else LOCAL_FILE_REF,
                    )
                    put("path", attachment.relativePath)
                    put("mediaType", attachment.mediaType)
                    put("name", attachment.name)
                    put("bytes", attachment.bytes)
                    attachment.attachmentId?.let { put("attachmentId", it) }
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                })
            }
        })
    }
}

internal fun replaceLocalUserModelMessageText(
    message: JsonObject,
    visibleText: String,
): JsonObject {
    val clean = visibleText.trim()
    val content = message["content"]
    val nextContent = if (content is JsonArray) {
        buildJsonArray {
            if (clean.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", clean)
                })
            }
            content.forEach { part ->
                val obj = part as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull != "text") add(part)
            }
        }
    } else {
        JsonPrimitive(clean)
    }
    return JsonObject(message.toMutableMap().apply {
        put("role", JsonPrimitive("user"))
        put("content", nextContent)
    })
}

internal fun hasLocalImageRefs(messages: List<JsonObject>): Boolean =
    messages.any(::hasLocalImageRefs)

private fun hasLocalImageRefs(message: JsonObject): Boolean =
    hasLocalRef(message, LOCAL_IMAGE_REF)

private fun hasLocalFileRefs(message: JsonObject): Boolean =
    hasLocalRef(message, LOCAL_FILE_REF)

private fun hasLocalRef(message: JsonObject, type: String): Boolean =
    (message["content"] as? JsonArray).orEmpty().any { part ->
        (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == type
    }

internal suspend fun prepareLocalMultimodalMessages(
    messages: List<JsonObject>,
    workspaceRoot: File,
    mode: LocalImageInputMode,
    budget: LocalImageRequestBudget = LocalImageRequestBudget(
        maxImages = 8,
        maxRawBytes = 32L * 1024L * 1024L,
    ),
    maxImageBytes: Long = MAX_NATIVE_IMAGE_BYTES,
): List<JsonObject> = withContext(Dispatchers.IO) {
    val activeImageMessageIndices = activeImageMessageIndices(messages, mode)
    val activeFileMessageIndices = activeFileMessageIndices(messages)
    val root = workspaceRoot.canonicalFile
    var activeImages = 0
    var activeRawBytes = 0L
    var activeDocumentChars = 0
    val reservedDirectImages = messages.mapIndexed { index, message ->
        if (index in activeImageMessageIndices) {
            (message["content"] as? JsonArray).orEmpty().count { part ->
                (part as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == LOCAL_IMAGE_REF
            }
        } else {
            0
        }
    }.sum()
    var pdfPageSlots = (budget.maxImages - reservedDirectImages).coerceAtLeast(0)

    messages.mapIndexed { messageIndex, message ->
        val content = message["content"] as? JsonArray ?: return@mapIndexed message
        val converted = buildJsonArray {
            content.forEach { part ->
                val obj = part as? JsonObject
                val type = obj?.get("type")?.jsonPrimitive?.contentOrNull

                if (type == LOCAL_FILE_REF) {
                    if (messageIndex !in activeFileMessageIndices) {
                        add(historicalFileReference(obj))
                        return@forEach
                    }
                    val relative = obj["path"]?.jsonPrimitive?.contentOrNull
                        ?: error("文件引用缺少工作区路径")
                    val file = File(root, relative).canonicalFile
                    require(file.toPath().startsWith(root.toPath())) { "文件引用越过工作区边界" }
                    require(file.isFile) { "文件附件不存在：$relative" }
                    require(file.length() in 1..MAX_LOCAL_DOCUMENT_BYTES) {
                        "文件附件超过 ${MAX_LOCAL_DOCUMENT_BYTES / 1024 / 1024} MB 解析上限：$relative"
                    }
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: file.name
                    val mediaType = obj["mediaType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                    val remainingChars = (MAX_LOCAL_DOCUMENT_CHARS_PER_REQUEST - activeDocumentChars)
                        .coerceAtLeast(1)
                    val extracted = LocalDocumentContent.extract(
                        file = file,
                        displayName = name,
                        mediaType = mediaType,
                        maxChars = minOf(MAX_LOCAL_DOCUMENT_CHARS_PER_FILE, remainingChars),
                    )
                    if (extracted.parsed && extracted.text.isNotBlank()) {
                        val body = buildString {
                            append("[附件内容：").append(name).append(" · ").append(extracted.formatLabel)
                            if (extracted.truncated) append(" · 已按上下文预算截断")
                            append("]\n").append(extracted.text)
                        }
                        activeDocumentChars += body.length
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", body)
                        })
                    }

                    val isPdf = name.endsWith(".pdf", ignoreCase = true) ||
                        mediaType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)
                    val shouldRenderPdf = isPdf &&
                        mode != LocalImageInputMode.TOOL &&
                        pdfPageSlots > 0 &&
                        (!extracted.parsed || extracted.text.length < MIN_RELIABLE_PDF_TEXT_CHARS)
                    if (shouldRenderPdf) {
                        val rendered = renderLocalPdfForModel(
                            file = file,
                            maxPages = minOf(pdfPageSlots, MAX_LOCAL_PDF_RENDER_PAGES),
                        )
                        add(buildJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                buildString {
                                    append("[PDF 页面视觉内容：").append(name)
                                    append("，共 ").append(rendered.pageCount).append(" 页")
                                    if (rendered.truncated) {
                                        append("；当前按本轮图片预算发送前 ").append(rendered.pages.size).append(" 页")
                                    }
                                    append("]")
                                },
                            )
                        })
                        rendered.pages.forEach { page ->
                            activeImages += 1
                            activeRawBytes += page.bytes.size
                            require(activeImages <= budget.maxImages) {
                                "本轮图片总量超过 ${budget.maxImages} 张预算"
                            }
                            require(activeRawBytes <= budget.maxRawBytes) {
                                "本轮图片原始数据超过 ${budget.maxRawBytes / 1024 / 1024} MB 预算"
                            }
                            add(modelImageUrl(page.mediaType, page.bytes))
                        }
                        pdfPageSlots -= rendered.pages.size
                    } else if (!extracted.parsed || extracted.text.isBlank()) {
                        add(buildJsonObject {
                            put("type", "text")
                            put(
                                "text",
                                if (isPdf && mode == LocalImageInputMode.TOOL) {
                                    "[附件：$name（PDF）。未能可靠提取文本；扫描型或复杂 PDF 需要切换到支持图片理解的模型后按页面读取。工作区路径：$relative]"
                                } else {
                                    "[附件：$name（${extracted.formatLabel}）。当前文件已保存在工作区，未提取到可直接发送给模型的文本内容。工作区路径：$relative]"
                                },
                            )
                        })
                    }
                    return@forEach
                }

                if (type != LOCAL_IMAGE_REF) {
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
                require(file.length() in 1..minOf(maxImageBytes, MAX_NATIVE_IMAGE_BYTES)) {
                    "图片附件超过 ${minOf(maxImageBytes, MAX_NATIVE_IMAGE_BYTES) / 1_000_000} MB 直传上限：$relative"
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
                val bytes = readLocalImageBytesBounded(
                    file,
                    minOf(maxImageBytes, MAX_NATIVE_IMAGE_BYTES),
                )
                add(modelImageUrl(actualMediaType, bytes))
            }
        }
        JsonObject(message.toMutableMap().apply { put("content", converted) })
    }
}

private fun activeImageMessageIndices(
    messages: List<JsonObject>,
    mode: LocalImageInputMode,
): Set<Int> =
    if (mode == LocalImageInputMode.TOOL) emptySet()
    else activeUserMessageIndices(messages, ::hasLocalImageRefs)

private fun activeFileMessageIndices(messages: List<JsonObject>): Set<Int> =
    activeUserMessageIndices(messages, ::hasLocalFileRefs)

private fun activeUserMessageIndices(
    messages: List<JsonObject>,
    hasReference: (JsonObject) -> Boolean,
): Set<Int> {
    if (messages.isEmpty()) return emptySet()
    fun roleAt(index: Int): String? =
        messages[index]["role"]?.jsonPrimitive?.contentOrNull

    if (roleAt(messages.lastIndex) == "user") {
        var start = messages.lastIndex
        while (start > 0 && roleAt(start - 1) == "user") start -= 1
        return (start..messages.lastIndex)
            .filter { index -> hasReference(messages[index]) }
            .toSet()
    }

    val latestUser = messages.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "user"
    }
    return if (latestUser >= 0 && hasReference(messages[latestUser])) setOf(latestUser) else emptySet()
}

private fun historicalFileReference(file: JsonObject): JsonObject {
    val name = file["name"]?.jsonPrimitive?.contentOrNull ?: "未命名文件"
    val path = file["path"]?.jsonPrimitive?.contentOrNull ?: "未知路径"
    val mediaType = file["mediaType"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
    return buildJsonObject {
        put("type", "text")
        put(
            "text",
            "[历史文件引用：$name；类型：$mediaType；工作区路径：$path。文件正文未重复注入，需要时重新附加或在工作模式读取。]",
        )
    }
}

private fun modelImageUrl(mediaType: String, bytes: ByteArray): JsonObject {
    val data = Base64.getEncoder().encodeToString(bytes)
    return buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject {
            put("url", "data:$mediaType;base64,$data")
            put("detail", "high")
        })
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


internal fun readLocalImageBytesBounded(file: File, maxBytes: Long): ByteArray {
    require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "图片读取上限无效" }
    val limit = maxBytes.toInt()
    file.inputStream().use { input ->
        val bytes = input.readNBytes(limit + 1)
        require(bytes.size <= limit) {
            "图片附件超过 ${maxBytes / 1024 / 1024} MB 直传上限：${file.name}"
        }
        return bytes
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
private const val MAX_LOCAL_DOCUMENT_BYTES = 20L * 1024L * 1024L
private const val MAX_LOCAL_DOCUMENT_CHARS_PER_FILE = 40_000
private const val MAX_LOCAL_DOCUMENT_CHARS_PER_REQUEST = 80_000
private const val MIN_RELIABLE_PDF_TEXT_CHARS = 1_200
private const val MAX_LOCAL_PDF_RENDER_PAGES = 6
