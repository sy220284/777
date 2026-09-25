package com.labteto.dshmobile.local

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Private spill storage for model-facing tool output retention.
 *
 * Spill files never enter the user workspace or Android backup. They are addressed by the original
 * tool call id through a hash, bounded per item, and removed with their owning Session.
 */
internal class LocalToolOutputStore(
    private val root: File,
    private val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    private val maxFilesPerSession: Int = DEFAULT_MAX_FILES_PER_SESSION,
    private val maxSessionBytes: Long = DEFAULT_MAX_SESSION_BYTES,
    private val maxGlobalBytes: Long = DEFAULT_MAX_GLOBAL_BYTES,
) {
    init {
        root.mkdirs()
    }

    data class Stored(val bytes: Int)

    @Synchronized
    fun store(sessionId: String, callId: String, content: String): Stored? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxOutputBytes) return null

        val dir = sessionDir(sessionId).apply { mkdirs() }
        val target = File(dir, key(callId) + ".txt")
        val temp = File(dir, "." + target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        pruneSession(dir)
        pruneGlobal()
        return Stored(bytes.size)
    }

    @Synchronized
    fun read(
        sessionId: String,
        callId: String,
        startByte: Int = 0,
        maxBytes: Int = DEFAULT_READ_BYTES,
    ): String {
        require(startByte >= 0) { "start_byte 必须大于等于 0" }
        require(maxBytes in MIN_READ_BYTES..MAX_READ_BYTES) {
            "max_bytes 必须在 $MIN_READ_BYTES–$MAX_READ_BYTES 之间"
        }

        val source = File(sessionDir(sessionId), key(callId) + ".txt")
        if (!source.isFile) return "未找到该工具调用的完整保留结果：$callId"

        val totalBytes = source.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (startByte >= totalBytes) return "工具结果没有第 $startByte 个 UTF-8 字节"

        RandomAccessFile(source, "r").use { file ->
            var alignedStart = startByte
            if (alignedStart > 0) {
                while (alignedStart < totalBytes) {
                    file.seek(alignedStart.toLong())
                    val value = file.read()
                    if (value < 0 || (value and 0xC0) != 0x80) break
                    alignedStart += 1
                }
            }
            if (alignedStart >= totalBytes) return "工具结果没有可从第 $startByte 个字节开始读取的完整字符"

            val requested = minOf(maxBytes, totalBytes - alignedStart)
            val bytes = ByteArray(requested)
            file.seek(alignedStart.toLong())
            file.readFully(bytes)
            val safeLength = safeUtf8PrefixLength(bytes)
            if (safeLength <= 0) return "当前读取窗口无法容纳一个完整 UTF-8 字符，请增大 max_bytes"

            val nextByte = alignedStart + safeLength
            val body = String(bytes, 0, safeLength, Charsets.UTF_8)
            return buildString {
                append("工具调用 ").append(callId)
                append(" · UTF-8 字节 ").append(alignedStart)
                append("–").append(nextByte - 1)
                append(" / ").append(totalBytes)
                if (nextByte < totalBytes) append("（后续仍有内容）")
                append("\n\n")
                append(body)
                if (nextByte < totalBytes) {
                    append("\n\n继续读取可把 start_byte 设为 ")
                    append(nextByte)
                    append("。")
                }
            }
        }
    }

    private fun safeUtf8PrefixLength(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var leadIndex = bytes.lastIndex
        while (leadIndex >= 0 && isUtf8Continuation(bytes[leadIndex])) leadIndex -= 1
        if (leadIndex < 0) return 0
        val lead = bytes[leadIndex].toInt() and 0xFF
        val expected = when {
            lead < 0x80 -> 1
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> 1
        }
        return if (bytes.size - leadIndex < expected) leadIndex else bytes.size
    }

    private fun isUtf8Continuation(value: Byte): Boolean =
        (value.toInt() and 0xC0) == 0x80

    @Synchronized
    fun deleteSession(sessionId: String) {
        sessionDir(sessionId).deleteRecursively()
    }

    private fun sessionDir(sessionId: String): File = File(root, key(sessionId))

    private fun pruneSession(dir: File) {
        var keptBytes = 0L
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending(File::lastModified)
            .forEachIndexed { index, file ->
                val nextBytes = keptBytes + file.length()
                if (index >= maxFilesPerSession || nextBytes > maxSessionBytes) {
                    file.delete()
                } else {
                    keptBytes = nextBytes
                }
            }
    }

    private fun pruneGlobal() {
        var keptBytes = 0L
        root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val nextBytes = keptBytes + file.length()
                if (nextBytes > maxGlobalBytes) {
                    file.delete()
                } else {
                    keptBytes = nextBytes
                }
            }
        root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .filter { it.listFiles().isNullOrEmpty() }
            .forEach(File::delete)
    }

    private fun key(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val DEFAULT_READ_BYTES = 24 * 1024
        const val MAX_READ_BYTES = 48 * 1024
        private const val MIN_READ_BYTES = 1_024
        private const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
        private const val MAX_FILES_PER_SESSION = 64
    }
}
