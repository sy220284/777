package com.labteto.dshmobile.harness.session

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/** Immutable rotated segments only. Global byte and entry caps bound memory across all sessions. */
internal object CompressedEventSegmentCache {
    private data class Key(val path: String, val length: Long, val modified: Long)
    private val entries = LinkedHashMap<Key, ByteArray>(4, 0.75f, true)
    private var bytes = 0L
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 4

    fun read(source: File, maxDecodedBytes: Long): ByteArray {
        val key = Key(source.canonicalPath, source.length(), source.lastModified())
        synchronized(entries) { entries[key]?.let {
            if (it.size.toLong() > maxDecodedBytes) throw IOException("压缩日志超过解压大小上限")
            return it
        } }
        val output = ByteArrayOutputStream()
        GZIPInputStream(source.inputStream().buffered()).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxDecodedBytes) {
                    throw IOException("压缩日志超过解压大小上限")
                }
                output.write(buffer, 0, count)
            }
        }
        val decoded = output.toByteArray()
        if (decoded.size <= MAX_BYTES) synchronized(entries) {
            // Source rotation/replacement invalidates old signatures, even at the same path.
            val oldKeys = entries.keys.filter { it.path == key.path }
            oldKeys.forEach { old -> bytes -= entries.remove(old)?.size ?: 0 }
            while (entries.isNotEmpty() && (entries.size >= MAX_ENTRIES || bytes + decoded.size > MAX_BYTES)) {
                val oldest = entries.entries.first()
                bytes -= oldest.value.size
                entries.remove(oldest.key)
            }
            entries[key] = decoded
            bytes += decoded.size
        }
        return decoded
    }

    fun invalidate(path: String) = synchronized(entries) {
        entries.keys.filter { it.path == path || it.path.startsWith("$path.part-") }.forEach {
            bytes -= entries.remove(it)?.size ?: 0
        }
    }
}
