package com.labteto.dshmobile.local

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class LocalImageAttachmentReferences(
    val attachmentIds: Set<String>,
    val paths: Set<String>,
)

internal data class LocalAttachmentCleanupResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val retainedImageBytes: Long,
)

internal fun collectLocalImageAttachmentReferences(
    events: Sequence<LocalSessionEventLog.Event>,
    extraMessages: List<JsonObject> = emptyList(),
): LocalImageAttachmentReferences {
    val ids = linkedSetOf<String>()
    val paths = linkedSetOf<String>()

    fun visit(element: JsonElement?) {
        when (element) {
            is JsonObject -> {
                if ((element["type"] as? JsonPrimitive)?.contentOrNull == LOCAL_IMAGE_REF) {
                    (element["attachmentId"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let(ids::add)
                    (element["path"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let(paths::add)
                }
                element.values.forEach { child -> visit(child) }
            }
            is JsonArray -> element.forEach { child -> visit(child) }
            else -> Unit
        }
    }

    events.forEach { event -> visit(event.data) }
    extraMessages.forEach { message -> visit(message) }
    return LocalImageAttachmentReferences(ids, paths)
}

internal fun mergeLocalImageAttachmentReferences(
    values: Iterable<LocalImageAttachmentReferences>,
): LocalImageAttachmentReferences {
    val ids = linkedSetOf<String>()
    val paths = linkedSetOf<String>()
    values.forEach { value ->
        ids += value.attachmentIds
        paths += value.paths
    }
    return LocalImageAttachmentReferences(ids, paths)
}

internal fun cleanupLocalImageAttachments(
    workspaceRoot: File,
    references: LocalImageAttachmentReferences,
    nowMillis: Long = System.currentTimeMillis(),
    graceMillis: Long = DEFAULT_LOCAL_IMAGE_GRACE_MILLIS,
    maxImageBytes: Long = DEFAULT_LOCAL_IMAGE_CACHE_BYTES,
): LocalAttachmentCleanupResult {
    require(graceMillis >= 0L) { "图片附件保留期不能为负数" }
    require(maxImageBytes > 0L) { "图片附件缓存上限必须大于 0" }

    val root = workspaceRoot.canonicalFile
    val attachments = File(root, ".dsh/attachments").canonicalFile
    if (!attachments.exists()) return LocalAttachmentCleanupResult(0, 0L, 0L)
    require(attachments.toPath().startsWith(root.toPath())) { "附件目录越过工作区边界" }

    val images = attachments.walkTopDown()
        .filter(File::isFile)
        .filter { file ->
            runCatching { file.canonicalFile.toPath().startsWith(attachments.toPath()) }
                .getOrDefault(false)
        }
        .filter { file -> sniffLocalImageMediaType(file) != null }
        .toList()

    fun relativePath(file: File): String =
        file.canonicalFile.relativeTo(root).invariantSeparatorsPath

    fun isReferenced(file: File): Boolean {
        val path = relativePath(file)
        val id = file.name.substringBeforeLast('.', file.name)
        return path in references.paths || id in references.attachmentIds
    }

    val referenced = images.filter(::isReferenced).toSet()
    val unreferenced = images.filterNot(referenced::contains).toMutableList()
    var totalBytes = images.sumOf(File::length)
    var deletedFiles = 0
    var deletedBytes = 0L

    fun delete(file: File) {
        val size = file.length()
        if (file.delete()) {
            deletedFiles += 1
            deletedBytes += size
            totalBytes = (totalBytes - size).coerceAtLeast(0L)
        }
    }

    unreferenced
        .filter { file -> nowMillis - file.lastModified() >= graceMillis }
        .sortedBy(File::lastModified)
        .forEach(::delete)

    if (totalBytes > maxImageBytes) {
        unreferenced
            .filter(File::exists)
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (totalBytes > maxImageBytes) delete(file)
            }
    }

    return LocalAttachmentCleanupResult(
        deletedFiles = deletedFiles,
        deletedBytes = deletedBytes,
        retainedImageBytes = totalBytes,
    )
}

internal const val DEFAULT_LOCAL_IMAGE_CACHE_BYTES = 512L * 1024L * 1024L
internal const val DEFAULT_LOCAL_IMAGE_GRACE_MILLIS = 7L * 24L * 60L * 60L * 1000L
