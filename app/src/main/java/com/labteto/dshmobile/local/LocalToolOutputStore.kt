package com.labteto.dshmobile.local

import java.io.File
import java.security.MessageDigest

/**
 * Private spill storage for model-facing tool output retention.
 *
 * Spill files never enter the user workspace or Android backup. They are addressed by the original
 * tool call id through a hash, bounded per item, and removed with their owning Session.
 */
internal class LocalToolOutputStore(
    private val root: File,
) {
    init {
        root.mkdirs()
    }

    data class Stored(val bytes: Int)

    @Synchronized
    fun store(sessionId: String, callId: String, content: String): Stored? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_OUTPUT_BYTES) return null

        val dir = sessionDir(sessionId).apply { mkdirs() }
        val target = File(dir, key(callId) + ".txt")
        val temp = File(dir, "." + target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
        prune(dir)
        return Stored(bytes.size)
    }

    @Synchronized
    fun read(
        sessionId: String,
        callId: String,
        startLine: Int = 1,
        endLine: Int = startLine + DEFAULT_READ_LINES - 1,
    ): String {
        require(startLine >= 1) { "start_line 必须大于等于 1" }
        require(endLine >= startLine) { "end_line 不能小于 start_line" }
        require(endLine - startLine + 1 <= MAX_READ_LINES) { "单次最多读取 $MAX_READ_LINES 行" }

        val source = File(sessionDir(sessionId), key(callId) + ".txt")
        if (!source.isFile) return "未找到该工具调用的完整保留结果：$callId"

        val body = StringBuilder()
        var lineNumber = 0
        var emitted = 0
        var hasMore = false
        source.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber += 1
                if (lineNumber < startLine) continue
                if (lineNumber > endLine) {
                    hasMore = true
                    break
                }
                if (emitted > 0) body.append('\n')
                body.append(line)
                emitted += 1
            }
        }
        if (emitted == 0) return "工具结果没有第 $startLine 行"
        return buildString {
            append("工具调用 ").append(callId)
            append(" · 第 ").append(startLine).append("–").append(startLine + emitted - 1).append(" 行")
            if (hasMore) append("（后续仍有内容）")
            append("\n\n")
            append(body)
            if (hasMore) {
                append("\n\n继续读取可把 start_line 设为 ")
                append(startLine + emitted)
                append("。")
            }
        }
    }

    @Synchronized
    fun deleteSession(sessionId: String) {
        sessionDir(sessionId).deleteRecursively()
    }

    private fun sessionDir(sessionId: String): File = File(root, key(sessionId))

    private fun prune(dir: File) {
        dir.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_FILES_PER_SESSION)
            .forEach(File::delete)
    }

    private fun key(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
        const val MAX_FILES_PER_SESSION = 64
        const val DEFAULT_READ_LINES = 400
        const val MAX_READ_LINES = 2_000
    }
}
