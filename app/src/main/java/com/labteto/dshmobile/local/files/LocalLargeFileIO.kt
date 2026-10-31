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
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/**
 * Workspace file mutations use a bounded transfer buffer and a sibling temporary file.
 * A failed edit, interrupted transfer, or duplicate replacement never touches the original.
 */
internal object LocalLargeFileIO {
    private const val BUFFER_CHARS = 32 * 1024
    private const val BUFFER_BYTES = 64 * 1024
    private val publishMonitors = Array(64) { Any() }

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
    fun writeChunk(target: File, content: ByteArray, offset: Long, finish: Boolean, sessionId: String?, workspaceRoot: File? = null): ChunkWriteResult {
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
        val baseline = File(directory, "." + target.name + ".write-baseline-" + id)
        if (offset == 0L) {
            val identity = withPublishLock(target, workspaceRoot) { targetIdentity(target) }
            Files.createFile(staged.toPath())
            try {
                Files.createFile(baseline.toPath())
                FileOutputStream(baseline).use { output ->
                    output.write(identity.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            } catch (error: Throwable) {
                Files.deleteIfExists(staged.toPath())
                Files.deleteIfExists(baseline.toPath())
                throw error
            }
        }
        require(Files.isRegularFile(staged.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isRegularFile(baseline.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "续写会话文件不存在或不安全"
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
                    withPublishLock(target, workspaceRoot) {
                        require(baseline.readText(Charsets.UTF_8) == targetIdentity(target)) {
                            "文件已被另一任务修改，拒绝覆盖；请重新读取并发起新续写"
                        }
                        preserveExecutable(target, staged)
                        moveAtomic(staged, target)
                        Files.deleteIfExists(baseline.toPath())
                    }
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
            return withPublishLock(target, workspaceRoot) {
                beforePublish()
                preserveExecutable(target, temporary.toFile())
                moveAtomic(temporary.toFile(), target)
                target.length()
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Bind a resumed upload to the original committed file version. */
    private fun targetIdentity(target: File): String {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return "absent"
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "目标文件类型已变化" }
        val attrs = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        val change = runCatching {
            Files.getAttribute(target.toPath(), "unix:ctime", LinkOption.NOFOLLOW_LINKS).toString()
        }.getOrElse {
            val checksum = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { source ->
                val bytes = ByteArray(BUFFER_BYTES)
                while (true) {
                    checkInterrupted()
                    val n = source.read(bytes)
                    if (n < 0) break
                    checksum.update(bytes, 0, n)
                }
            }
            checksum.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return attrs.fileKey().toString() + "|" + attrs.size() + "|" +
            attrs.lastModifiedTime() + "|" + change
    }

    /**
     * Shared publish lock covers plain write/edit and chunk finalization, including
     * distinct LocalWorkspace objects. Locks live in app-private storage, never
     * beside user files in shared storage.
     */
    private fun <T> withPublishLock(target: File, workspaceRoot: File?, action: () -> T): T {
        val dir = File(workspaceRoot?.canonicalFile ?: requireNotNull(target.parentFile), ".dsh/write-locks")
        require(dir.isDirectory || dir.mkdirs()) { "无法创建提交锁目录" }
        val root = workspaceRoot?.canonicalFile
        if (root != null) {
            require(!Files.isSymbolicLink(dir.toPath()) &&
                dir.canonicalFile.toPath().startsWith(root.toPath())) { "提交锁目录不安全" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val lockPath = File(dir, "$digest.lck").toPath()
        val monitor = publishMonitors[(digest.hashCode() and Int.MAX_VALUE) % publishMonitors.size]
        return synchronized(monitor) {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.lock().use { action() }
            }
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
