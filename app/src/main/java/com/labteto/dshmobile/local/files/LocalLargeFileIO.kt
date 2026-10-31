package com.labteto.dshmobile.local.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Workspace file mutations use a bounded transfer buffer and a sibling temporary file.
 * A failed edit, interrupted transfer, or duplicate replacement never touches the original.
 */
internal object LocalLargeFileIO {
    private const val BUFFER_CHARS = 32 * 1024
    private const val BUFFER_BYTES = 64 * 1024

    fun writeText(target: File, content: String): Long =
        commit(target) { output ->
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

    fun writeStream(target: File, source: InputStream, progress: (Long) -> Unit = {}): Long =
        commit(target) { output ->
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
    fun replaceUnique(target: File, oldText: String, newText: String, beforePublish: () -> Unit): Long {
        require(oldText.isNotEmpty()) { "待替换内容不能为空" }
        return commit(target, beforePublish) { output ->
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
    fun writeChunk(target: File, content: String, offset: Long, finish: Boolean): Long {
        require(offset >= 0L) { "写入字节偏移不能为负数" }
        val directory = target.parentFile ?: error("文件缺少父目录")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建文件目录" }
        val staged = File(directory, "." + target.name + ".write-in-progress")
        require(!Files.isSymbolicLink(staged.toPath())) { "分块写入暂存文件不安全" }
        if (offset == 0L) {
            FileOutputStream(staged, false).use { output -> output.fd.sync() }
        } else {
            require(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "没有可续写的文件，请从 offset=0 开始"
            }
            require(staged.length() == offset) { "分块写入偏移已过期，请读取最新 next_byte" }
        }
        FileOutputStream(staged, true).use { output ->
            val writer = OutputStreamWriter(output, Charsets.UTF_8)
            var start = 0
            while (start < content.length) {
                checkInterrupted()
                val length = minOf(BUFFER_CHARS, content.length - start)
                writer.write(content, start, length)
                start += length
            }
            writer.flush()
            output.fd.sync()
        }
        val next = staged.length()
        if (finish) {
            preserveExecutable(target, staged)
            moveAtomic(staged, target)
        }
        return next
    }

    private fun commit(
        target: File,
        beforePublish: () -> Unit = {},
        write: (FileOutputStream) -> Unit,
    ): Long {
        val directory = target.parentFile ?: error("文件缺少父目录")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建文件目录" }
        val temporary = Files.createTempFile(directory.toPath(), ".write-", ".tmp")
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
