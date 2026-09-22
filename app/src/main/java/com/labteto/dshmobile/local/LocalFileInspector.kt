package com.labteto.dshmobile.local

import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal class LocalFileInspector(private val workspaceRoot: File) {
    fun inspect(relativePath: String): String {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "文件检查只接受工作区相对路径"
        }
        val root = workspaceRoot.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "拒绝检查工作区之外的文件" }
        require(file.isFile) { "文件不存在：$relativePath" }

        val mime = runCatching { Files.probeContentType(file.toPath()) }.getOrNull().orEmpty()
        val sha256 = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        val image = BitmapFactory.Options().also { options ->
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
        }

        return buildString {
            appendLine("path=$relativePath")
            appendLine("bytes=${file.length()}")
            appendLine("mime=${mime.ifBlank { image.outMimeType.orEmpty().ifBlank { "unknown" } }}")
            appendLine("sha256=$sha256")
            if (image.outWidth > 0 && image.outHeight > 0) {
                appendLine("width=${image.outWidth}")
                appendLine("height=${image.outHeight}")
                appendLine("image_mime=${image.outMimeType.orEmpty()}")
                appendExif(file)
            }
        }.trimEnd()
    }

    @Suppress("DEPRECATION")
    private fun StringBuilder.appendExif(file: File) {
        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull() ?: return
        val tags = listOf(
            "exif_make" to ExifInterface.TAG_MAKE,
            "exif_model" to ExifInterface.TAG_MODEL,
            "exif_datetime" to ExifInterface.TAG_DATETIME,
            "exif_orientation" to ExifInterface.TAG_ORIENTATION,
            "exif_focal_length" to ExifInterface.TAG_FOCAL_LENGTH,
            "exif_exposure_time" to ExifInterface.TAG_EXPOSURE_TIME,
            "exif_iso" to ExifInterface.TAG_ISO,
        )
        tags.forEach { (label, tag) ->
            exif.getAttribute(tag)?.takeIf(String::isNotBlank)?.let { value ->
                appendLine("$label=$value")
            }
        }
        val latLong = FloatArray(2)
        if (exif.getLatLong(latLong)) {
            appendLine("exif_latitude=${latLong[0]}")
            appendLine("exif_longitude=${latLong[1]}")
        }
    }
}
