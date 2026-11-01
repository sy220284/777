package com.labteto.dshmobile.local.model

import java.io.File
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.Base64

/**
 * Materialize the data URL required by inline model protocols without first
 * loading the source into a ByteArray and then allocating a second Base64 array.
 * The resulting String is owned only by the request, never by session storage.
 */
internal fun encodeLocalFileDataUrl(file: File, mediaType: String, maxBytes: Long): String {
    require(maxBytes in 1L..Int.MAX_VALUE.toLong()) { "文件直传容量预算无效" }
    val size = file.length()
    require(file.isFile && size in 1..maxBytes) {
        "文件超过直传容量或不存在：${file.name}"
    }
    val prefix = "data:$mediaType;base64,"
    val capacity = prefix.length.toLong() + ((size + 2) / 3) * 4
    require(capacity <= Int.MAX_VALUE) { "文件编码结果超过内存地址范围" }
    val content = StringBuilder(capacity.toInt()).append(prefix)
    // JDK Base64 streams ASCII bytes. Append small encoded blocks directly to
    // the final buffer instead of creating a second whole-file byte array.
    val sink = object : OutputStream() {
        override fun write(value: Int) {
            content.append((value and 0xff).toChar())
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            content.append(String(bytes, offset, length, Charsets.US_ASCII))
        }
    }
    var copied = 0L
    file.inputStream().use { source ->
        Base64.getEncoder().wrap(sink).use { encoded ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("文件编码已取消")
                }
                val count = source.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= maxBytes) { "读取过程中文件超过直传容量：${file.name}" }
                encoded.write(buffer, 0, count)
            }
        }
    }
    require(copied == size && file.length() == size) {
        "文件在编码期间发生变化，请重新读取：${file.name}"
    }
    return content.toString()
}
