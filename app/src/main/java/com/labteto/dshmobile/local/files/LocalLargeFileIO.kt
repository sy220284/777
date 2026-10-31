package com.labteto.dshmobile.local.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Workspace file mutations use a bounded transfer buffer and a sibling temporary file.
 * A failed edit, interrupted transfer, or duplicate replacement never touches the original.
 */
internal object LocalLargeFileIO {
    private const val BUFFER_CHARS = 32 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    fun writeText(target: File, content: String, workspaceRoot: File? = null): Long =
        commit(target, workspaceRoot = workspaceRoot) { output ->
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            var offset = 0
            while (offset < content.length) {
                checkInterrupted()
                val count = minOf(BUFFER_CHARS, content.length - offset)
                writer.write(content, offset, count)
                offset += count
            }
            writer.flush()
        }

    fun writeStream(target: File, source: InputStream, progress: (Long) -> Unit = {}, workspaceRoot: File? = null): Long =
        commit(target, workspaceRoot = workspaceRoot) { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var total = 0L
            source.use { input ->
                while (true) {
                    checkInterrupted()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    total += n
                    progress(total)
                }
            }
        }

    /** Exact UTF-16 literal semantics match String.indexOf/replaceRange without loading the file. */
    fun replaceUnique(target: File, oldText: String, newText: String, workspaceRoot: File? = null, beforePublish: () -> Unit): Long {
        require(oldText.isNotEmpty()) { "待替换内容不能为空" }
        return commit(target, beforePublish, workspaceRoot) { output ->
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            val buffer = CharArray(BUFFER_CHARS)
            val pending = StringBuilder()
            var replacements = 0

            fun process(final: Boolean) {
                while (true) {
                    val match = pending.indexOf(oldText)
                    if (match >= 0) {
                        require(replacements == 0) { "待替换内容出现多次，请提供更长的唯一片段" }
                        writer.append(pending, 0, match)
                        writer.write(newText)
                        pending.delete(0, match + oldText.length)
                        replacements++
                        continue
                    }
                    val safe = if (final) pending.length else
                        (pending.length - oldText.length + 1).coerceAtLeast(0)
                    if (safe > 0) {
                        writer.append(pending, 0, safe)
                        pending.delete(0, safe)
                    }
                    break
                }
            }

            target.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    checkInterrupted()
                    val n = reader.read(buffer)
                    if (n < 0) break
                    pending.append(buffer, 0, n)
                    process(false)
                }
            }
            process(true)
            require(replacements == 1) { "文件中没有找到待替换内容" }
            writer.flush()
        }
    }

    /**
     * A durable byte-offset append session. Existing final files remain unchanged until
     * the last chunk is fsynced and promoted. The client can resume from next_byte after
     * a process restart; unexpected offsets cannot silently corrupt the staged data.
     */
    data class ChunkWriteResult(val nextByte: Long, val sessionId: String)

    /**
     * Each upload owns a randomized durable stage. The continuation token is mandatory
     * after byte zero, so an unrelated task cannot silently inherit a same-sized stage.
     * An OS file lock serializes multiple LocalWorkspace instances and processes.
     */
    fun writeChunk(target: File, content: ByteArray, offset: Long, finish: Boolean, sessionId: String?): ChunkWriteResult {
        require(offset >= 0L) { "写入字节偏移不能为负数" }
        val directory = target.parentFile ?: error("文件缺少父目录")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建文件目录" }
        val id = if (offset == 0L) {
            require(sessionId == null) { "新续写会话不能复用旧 session_id" }
            UUID.randomUUID().toString()
        } else {
            val token = requireNotNull(sessionId) { "继续写入必须传入上次返回的 session_id" }
            require(runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)) {
                "续写会话标识无效"
            }
            token
        }
        val staged = File(directory, "." + target.name + ".write-in-progress-" + id)
        if (offset == 0L) Files.createFile(staged.toPath())
        require(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "续写暂存文件不存在或不安全"
        }
        FileChannel.open(staged.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                require(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "续写会话已结束，请重新开始"
                }
                require(channel.size() == offset) {
                    "分块写入偏移已过期，请读取最新 next_byte"
                }
                channel.position(offset)
                val buffer = ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) {
                    checkInterrupted()
                    channel.write(buffer)
                }
                channel.force(true)
                if (finish) {
                    preserveExecutable(target, staged)
                    moveAtomic(staged, target)
                }
            }
        }
        return ChunkWriteResult(offset + content.size.toLong(), id)
    }

    private fun commit(
        target: File,
        beforePublish: () -> Unit = {},
        workspaceRoot: File? = null,
        write: (FileOutputStream) -> Unit,
    ): Long {
        val directory = target.parentFile ?: error("文件缺少父目录")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建文件目录" }
        // Only the app-private owned staging directory is eligible for startup cleanup.
        // External/shared-storage writes retain sibling stages for atomic same-volume rename.
        val root = workspaceRoot?.canonicalFile
        val tempDirectory = if (root != null && target.canonicalFile.toPath().startsWith(root.toPath())) {
            File(root, ".dsh/write-temp").also {
                require(it.isDirectory || it.mkdirs()) { "无法创建写入暂存目录" }
                require(!Files.isSymbolicLink(it.toPath()) &&
                    it.canonicalFile.toPath().startsWith(root.toPath())) { "写入暂存目录不安全" }
            }
        } else directory
        val temporary = Files.createTempFile(tempDirectory.toPath(), ".write-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                write(output)
                output.fd.sync()
            }
            beforePublish()
            preserveExecutable(target, temporary.toFile())
            moveAtomic(temporary.toFile(), target)
            return target.length()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun preserveExecutable(target: File, temporary: File) {
        if (target.isFile && target.canExecute()) {
            check(temporary.setExecutable(true, true)) { "无法保留原文件执行权限" }
        }
    }

    private fun moveAtomic(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("文件处理已取消")
    }
}
