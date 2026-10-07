package com.labteto.dshmobile.local.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal class LocalAttachmentImporter(
    private val context: Context,
    private val workspace: LocalWorkspace,
    private val maxAttachmentBytes: Long = 20L * 1024L * 1024L,
) {
    suspend fun import(uri: Uri): LocalImportedAttachment {
        val resolver = context.contentResolver
        var displayName: String? = null
        var declaredSize: Long? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }

        if ((declaredSize ?: 0L) > maxAttachmentBytes) {
            error("附件超过 ${maxAttachmentBytes / 1024 / 1024} MB 上限")
        }

        val safeName = (displayName ?: "attachment-${System.currentTimeMillis()}")
            .replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fff]"), "_")
            .take(120)
            .ifBlank { "attachment-${System.currentTimeMillis()}" }
        val declaredMediaType = resolver.getType(uri)?.lowercase() ?: "application/octet-stream"
        val dir = File(workspace.path, ".dsh/attachments").apply { mkdirs() }
        val incoming = File(dir, ".incoming-${UUID.randomUUID()}")
        val digest = MessageDigest.getInstance("SHA-256")

        val input = resolver.openInputStream(uri) ?: error("无法读取所选附件")
        try {
            currentCoroutineContext().ensureActive()
            input.use { source ->
                incoming.outputStream().use { output ->
                    copyAttachmentCancellably(
                        source = source,
                        output = output,
                        maxAttachmentBytes = maxAttachmentBytes,
                        digest = digest,
                    )
                }
            }
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            incoming.delete()
            throw error
        }

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
        if (target.exists()) {
            normalized.file.delete()
        } else if (!normalized.file.renameTo(target)) {
            normalized.file.copyTo(target, overwrite = false)
            normalized.file.delete()
        }
        if (normalized.file != incoming) incoming.delete()

        return LocalImportedAttachment(
            name = displayName ?: safeName,
            relativePath = target.relativeTo(File(workspace.path)).invariantSeparatorsPath,
            mediaType = mediaType,
            bytes = target.length(),
            attachmentId = attachmentId,
            width = imageMetadata?.width,
            height = imageMetadata?.height,
        )
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
        try {
            normalized.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "图片格式转换失败"
                }
            }
            require(normalized.length() in 1..maxAttachmentBytes) {
                "转换后的图片超过 ${maxAttachmentBytes / 1024 / 1024} MB 上限"
            }
            return NormalizedImportedFile(
                file = normalized,
                imageMetadata = LocalImageMetadata(
                    mediaType = "image/jpeg",
                    width = bitmap.width,
                    height = bitmap.height,
                ),
            )
        } catch (error: Throwable) {
            normalized.delete()
            throw error
        } finally {
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
        const val MAX_NORMALIZATION_PIXELS = 24_000_000L
    }
}


internal suspend fun copyAttachmentCancellably(
    source: InputStream,
    output: OutputStream,
    maxAttachmentBytes: Long,
    digest: MessageDigest,
): Long = runInterruptible(Dispatchers.IO) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("附件导入已取消")
        val read = source.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxAttachmentBytes) {
            error("附件超过 ${maxAttachmentBytes / 1024 / 1024} MB 上限")
        }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    total
}
