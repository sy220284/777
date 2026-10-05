package com.labteto.dshmobile.local.attachment

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.labteto.dshmobile.local.model.LocalImageMetadata
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

        val imageMetadata = inspectImportedImage(incoming)
        if (declaredMediaType.startsWith("image/") && imageMetadata == null) {
            incoming.delete()
            error("所选文件不是可用的 PNG/JPEG/WebP/GIF 图片")
        }
        if (imageMetadata != null) {
            try {
                validateLocalImageMetadata(imageMetadata)
            } catch (error: Throwable) {
                incoming.delete()
                throw error
            }
        }

        val mediaType = imageMetadata?.mediaType ?: declaredMediaType
        val attachmentId = digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
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
            incoming.delete()
            throw error
        }
        val target = File(dir, attachmentId + extension?.let { ".$it" }.orEmpty())
        if (target.exists()) {
            incoming.delete()
        } else if (!incoming.renameTo(target)) {
            incoming.copyTo(target, overwrite = false)
            incoming.delete()
        }

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

    private fun inspectImportedImage(file: File): LocalImageMetadata? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val mediaType = options.outMimeType?.lowercase()
            ?.takeIf { it in SUPPORTED_LOCAL_IMAGE_TYPES }
            ?: return null
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return null
        return LocalImageMetadata(mediaType = mediaType, width = width, height = height)
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
