package com.labteto.dshmobile.local.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import com.labteto.dshmobile.local.model.LocalImageMetadata
import com.labteto.dshmobile.local.model.MAX_LOCAL_IMAGE_EDGE
import com.labteto.dshmobile.local.model.MAX_LOCAL_IMAGE_PIXELS
import com.labteto.dshmobile.local.model.SUPPORTED_LOCAL_IMAGE_TYPES
import com.labteto.dshmobile.local.model.validateLocalImageMetadata
import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal class LocalAttachmentImporter(
    private val context: Context,
    private val workspace: LocalWorkspace,
) {
    suspend fun import(uri: Uri): LocalImportedAttachment {
        val resolver = context.contentResolver
        var displayName: String? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
            }
        }

        val mediaType = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
        val input = resolver.openInputStream(uri) ?: error("无法读取所选附件")
        return importStream(input, displayName, mediaType)
    }

    /** MCP bytes enter the same validated, content-addressed attachment lifecycle as picked files. */
    suspend fun importImageBytes(bytes: ByteArray, mediaType: String): LocalImportedAttachment {
        require(mediaType.startsWith("image/") && bytes.isNotEmpty() &&
            bytes.size <= LocalMessageMediaPolicy.MAX_GENERATED_IMAGE_BYTES) { "MCP 图片类型或大小无效" }
        return importStream(bytes.inputStream(), "MCP图片", mediaType).also {
            require(it.width != null && it.height != null) { "MCP 图片无法解码为可用图片附件" }
        }
    }

    private suspend fun importStream(input: InputStream, displayName: String?, originalMediaType: String): LocalImportedAttachment {
        val safeName = (displayName ?: "attachment-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]"), "_")
            .take(120)
            .ifBlank { "attachment-${System.currentTimeMillis()}" }
        // SVG is text/XML and should stay a document attachment instead of entering the raster-image path.
        val declaredMediaType = if (
            originalMediaType == "image/svg+xml" ||
            safeName.endsWith(".svg", ignoreCase = true)
        ) {
            "application/xml"
        } else {
            originalMediaType
        }
        val dir = File(workspace.path, ".dsh/attachments").apply { mkdirs() }
        val incoming = File(dir, ".incoming-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            currentCoroutineContext().ensureActive()
            input.use { source ->
                incoming.outputStream().use { output ->
                    copyAttachmentCancellably(
                        source = source,
                        output = output,
                        digest = digest,
                    )
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            incoming.delete()
            throw error
        }

        // Every failure after the initial copy must release temporary files, including
        // metadata probing, hashing, cancellation and a failed final rename.
        var normalizedTemp: File? = null
        try {
        val probe = inspectImportedImage(incoming, declaredMediaType)
        if (declaredMediaType.startsWith("image/") && probe == null) {
            incoming.delete()
            error("所选图片无法解码")
        }

        val normalized = try {
            if (probe != null && probe.mediaType !in SUPPORTED_LOCAL_IMAGE_TYPES) {
                normalizeImportedImage(incoming, probe, dir)
            } else {
                NormalizedImportedFile(
                    file = incoming,
                    imageMetadata = probe?.let {
                        LocalImageMetadata(
                            mediaType = it.mediaType,
                            width = it.width,
                            height = it.height,
                        )
                    },
                )
            }
        } catch (error: Throwable) {
            incoming.delete()
            throw error
        }

        normalizedTemp = normalized.file
        val imageMetadata = normalized.imageMetadata
        if (imageMetadata != null) {
            try {
                validateLocalImageMetadata(imageMetadata)
            } catch (error: Throwable) {
                normalized.file.delete()
                if (normalized.file != incoming) incoming.delete()
                throw error
            }
        }

        val mediaType = imageMetadata?.mediaType ?: declaredMediaType
        val attachmentId = sha256(normalized.file)
        val extension = when (mediaType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> safeName.substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            normalized.file.delete()
            if (normalized.file != incoming) incoming.delete()
            throw error
        }
        val target = File(dir, attachmentId + extension?.let { ".$it" }.orEmpty())
        commitImportedAttachmentFile(normalized.file, target)

        return LocalImportedAttachment(
            name = displayName ?: safeName,
            relativePath = target.relativeTo(File(workspace.path)).invariantSeparatorsPath,
            mediaType = mediaType,
            bytes = target.length(),
            attachmentId = attachmentId,
            width = imageMetadata?.width,
            height = imageMetadata?.height,
        )
        } finally {
            incoming.delete()
            normalizedTemp?.takeIf { it != incoming }?.delete()
        }
    }

    private data class ImportedImageProbe(
        val mediaType: String,
        val width: Int,
        val height: Int,
    )

    private data class NormalizedImportedFile(
        val file: File,
        val imageMetadata: LocalImageMetadata?,
    )

    private fun inspectImportedImage(file: File, declaredMediaType: String): ImportedImageProbe? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        val mediaType = options.outMimeType?.lowercase()
            ?: declaredMediaType.takeIf { it.startsWith("image/") }
            ?: "image/unknown"
        return ImportedImageProbe(mediaType = mediaType, width = width, height = height)
    }

    private fun normalizeImportedImage(
        source: File,
        probe: ImportedImageProbe,
        dir: File,
    ): NormalizedImportedFile {
        require(probe.width in 1..MAX_LOCAL_IMAGE_EDGE && probe.height in 1..MAX_LOCAL_IMAGE_EDGE) {
            "图片边长不能超过 $MAX_LOCAL_IMAGE_EDGE 像素"
        }
        require(probe.width.toLong() * probe.height.toLong() <= MAX_LOCAL_IMAGE_PIXELS) {
            "图片总像素不能超过 ${MAX_LOCAL_IMAGE_PIXELS / 1_000_000} 百万像素"
        }
        var sample = 1
        while (
            (probe.width / sample).toLong() * (probe.height / sample).toLong() >
            MAX_NORMALIZATION_PIXELS
        ) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("所选图片无法解码")
        val normalized = File(dir, ".normalized-${UUID.randomUUID()}.jpg")
        val encodedBitmap = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { opaque ->
                Canvas(opaque).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        } else {
            bitmap
        }
        try {
            normalized.outputStream().use { output ->
                check(encodedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "图片格式转换失败"
                }
            }
            check(normalized.length() > 0L) { "转换后的图片为空" }
            return NormalizedImportedFile(
                file = normalized,
                imageMetadata = LocalImageMetadata(
                    mediaType = "image/jpeg",
                    width = encodedBitmap.width,
                    height = encodedBitmap.height,
                ),
            )
        } catch (error: Throwable) {
            normalized.delete()
            throw error
        } finally {
            if (encodedBitmap !== bitmap) encodedBitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val MAX_NORMALIZATION_PIXELS = 16_000_000L
    }
}


internal suspend fun copyAttachmentCancellably(
    source: InputStream,
    output: OutputStream,
    digest: MessageDigest,
): Long = runInterruptible(Dispatchers.IO) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("附件导入已取消")
        val read = source.read(buffer)
        if (read < 0) break
        total += read
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    total
}

/**
 * Publish an already completed, hashed attachment by a same-directory rename.
 *
 * Android app-private filesystems may reject hard links. No hard link is required here.
 * A source is either moved into place or, for a duplicate digest, checked byte-for-byte
 * against the existing file. Never request REPLACE_EXISTING.
 */
private val attachmentPublishLock = Any()

internal fun commitImportedAttachmentFile(source: File, target: File) {
    require(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        "附件临时文件不存在或不是普通文件"
    }
    require(source.parentFile.canonicalFile == target.parentFile.canonicalFile) {
        "附件发布必须在同一目录完成"
    }
    synchronized(attachmentPublishLock) {
        fun verifyExisting() {
            require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                sameImportedAttachmentContent(source, target)) {
                "同名附件内容不匹配或目标文件无效，已拒绝覆盖"
            }
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting()
            return
        }
        try {
            // Same-directory move publishes completed bytes without replacing an existing path.
            Files.move(source.toPath(), target.toPath())
        } catch (_: FileAlreadyExistsException) {
            verifyExisting()
        }
    }
}


/** Compare existing duplicate bytes without trusting its name or loading the file into RAM. */
private fun sameImportedAttachmentContent(first: File, second: File): Boolean {
    if (first.length() != second.length()) return false
    fun digest(file: File): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sha.update(buffer, 0, read)
            }
        }
        return sha.digest()
    }
    return MessageDigest.isEqual(digest(first), digest(second))
}
