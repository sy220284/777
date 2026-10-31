package com.labteto.dshmobile.local.files

import java.io.File
import java.io.FileInputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class LocalTextCheckpoint(val line: Int, val offset: Long, val digest: String)

/** Persist sparse line offsets and full source SHA-256 with bounded 64 KiB buffers. */
internal class LocalTextLineIndex(
    private val workspaceRoot: File,
    private val isAllowedSource: (File) -> Boolean = { it.toPath().startsWith(workspaceRoot.canonicalFile.toPath()) },
) {
    private companion object {
        const val VERSION = "text-lines-v1"
        const val STRIDE = 1024L
    }

    fun checkpoint(file: File, startLine: Int): LocalTextCheckpoint {
        val root = workspaceRoot.canonicalFile
        val source = file.canonicalFile
        require(source.isFile && isAllowedSource(source)) { "文本路径越界" }
        val directory = File(root, ".dsh/text-index")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建文本索引目录" }
        require(!Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.toPath().startsWith(root.toPath())) { "文本索引目录不安全" }
        val index = File(directory, sha(source.canonicalPath.toByteArray()) + ".index")
        val version = stamp(source)
        val ready = index.isFile && !Files.isSymbolicLink(index.toPath()) && runCatching {
            index.bufferedReader().use { input ->
                input.readLine() == VERSION && input.readLine() == version &&
                    input.readLine()?.length == 64
            }
        }.getOrDefault(false)
        if (!ready) build(source, index, version)
        val result = index.bufferedReader().use { input ->
            require(input.readLine() == VERSION && input.readLine() == version) { "文本索引失效" }
            val digest = input.readLine().orEmpty()
            require(digest.length == 64) { "文本索引摘要损坏" }
            var candidate = LocalTextCheckpoint(1, 0L, digest)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("索引查询已取消")
                val row = input.readLine() ?: break
                val separator = row.indexOf(':')
                require(separator > 0) { "文本索引记录损坏" }
                val line = row.substring(0, separator).toInt()
                val byte = row.substring(separator + 1).toLong()
                if (line <= startLine && line > candidate.line) candidate = LocalTextCheckpoint(line, byte, digest)
                if (line > startLine) break
            }
            candidate
        }
        require(stamp(source) == version) { "索引读取期间原文件发生变化" }
        return result
    }

    private fun build(source: File, index: File, expected: String) {
        val temp = File(index.parentFile, "." + index.name + "-" + UUID.randomUUID() + ".tmp")
        try {
            RandomAccessFile(temp, "rw").use { output ->
                output.write((VERSION + "\n" + expected + "\n").toByteArray(Charsets.UTF_8))
                val digestOffset = output.filePointer
                output.write(("0".repeat(64) + "\n").toByteArray(Charsets.US_ASCII))
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var consumed = 0L
                var line = 1L
                FileInputStream(source).use { input ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("文本索引已取消")
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buffer, 0, n)
                        for (i in 0 until n) {
                            if (buffer[i] == '\n'.code.toByte()) {
                                line++
                                if ((line - 1) % STRIDE == 0L && line <= Int.MAX_VALUE) {
                                    output.write(("$line:" + (consumed + i + 1) + "\n").toByteArray(Charsets.US_ASCII))
                                }
                            }
                        }
                        consumed += n
                    }
                }
                require(consumed == source.length() && stamp(source) == expected) { "文本索引期间源文件发生变化" }
                output.seek(digestOffset)
                output.write(digest.digest().hex().toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            try {
                Files.move(temp.toPath(), index.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun stamp(file: File): String {
        val info = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val sampleHash = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            val data = ByteArray(64 * 1024)
            val first = input.read(data)
            if (first > 0) sampleHash.update(data, 0, first)
            if (info.size() > data.size) {
                input.seek(info.size() - data.size)
                val last = input.read(data)
                if (last > 0) sampleHash.update(data, 0, last)
            }
        }
        // ctime detects in-place writes even when callers restore size and mtime.
        // On providers without unix:ctime, hash the entire source rather than trusting samples.
        val changeToken = runCatching {
            Files.getAttribute(file.toPath(), "unix:ctime", LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse {
            val full = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("索引校验已取消")
                    val count = input.read(chunk)
                    if (count < 0) break
                    full.update(chunk, 0, count)
                }
            }
            full.digest().hex()
        }
        return sha((file.canonicalPath + "|" + info.size() + "|" + info.lastModifiedTime() +
            "|" + info.fileKey() + "|" + changeToken + "|" + sampleHash.digest().hex()).toByteArray(Charsets.UTF_8))
    }

    private fun sha(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
}
