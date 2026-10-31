package com.labteto.dshmobile.local.tools

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Work reads large archives without extracting them onto disk or putting whole entries into model context.
 * Every page reports the exact continuation cursor; no preview can masquerade as full coverage.
 */
internal class LocalArchiveReader(private val root: File) {
    private data class Item(val name: String, val size: Long)

    fun list(path: String, cursor: Int = 0, limit: Int = 100, prefix: String = ""): String {
        require(cursor >= 0) { "cursor 不能为负数" }
        require(limit in 1..200) { "每页最多 200 个文件" }
        val entries = ArrayList<Item>(limit)
        var total = 0
        var unsafe = 0
        val archive = source(path)
        val visitItem: (Item) -> Unit = { item ->
            if (!safeName(item.name)) unsafe++
            else if (item.name.startsWith(prefix)) {
                if (total >= cursor && entries.size < limit) entries.add(item)
                total++
            }
        }
        if (isZipArchive(archive)) {
            // Listing requires metadata only: never open or inflate ZIP members.
            ZipFile(archive).use { zip ->
                val members = zip.entries()
                while (members.hasMoreElements()) {
                    val entry = members.nextElement()
                    if (!entry.isDirectory) visitItem(Item(normalMember(entry.name), entry.size))
                }
            }
        } else {
            // Compressed TAR enumeration is expensive. Keep an on-disk compact
            // index keyed by the exact archive identity for subsequent pages.
            indexedEntries(archive, visitItem)
        }
        val end = cursor.toLong() + entries.size
        val next = if (end < total) end.toString() else "none"
        return buildString {
            appendLine("归档：" + path)
            appendLine("匹配文件总数：" + total + "；本页：" + entries.size + "；cursor=" + cursor + "；next_cursor=" + next)
            if (unsafe > 0) appendLine("警告：跳过不安全路径 " + unsafe + " 项")
            entries.forEachIndexed { index, item ->
                appendLine((cursor + index + 1).toString() + ". " + item.name + " (" + item.size + " B)")
            }
            if (next != "none") appendLine("未遍历全部清单，请继续 archive_list cursor=" + next)
        }.trimEnd()
    }

    fun read(path: String, member: String, startChar: Long = 0L, maxChars: Int = 20_000): String {
        require(safeName(member)) { "归档内部路径不安全" }
        require(startChar >= 0L) { "start_char 不能为负数" }
        require(maxChars in 1..40_000) { "max_chars 必须在 1 到 40000 之间" }
        var result: String? = null
        val archive = source(path)
        val readMember: (Item, InputStream) -> Unit = { item, data ->
            result = if (isBinaryName(member)) {
                "成员 " + member + " 为二进制文件（" + item.size + " B），需使用专用解析工具"
            } else {
                val probe = data.readNBytes(512)
                if (probe.any { it == 0.toByte() }) {
                    "成员 " + member + " 包含二进制字节，不能直接作为源码文本"
                } else {
                    page(SequenceInputStream(probe.inputStream(), data), member, startChar, maxChars)
                }
            }
        }
        if (isZipArchive(archive)) {
            ZipFile(archive).use { zip ->
                // Do not open streams for unrelated members when reading the last
                // entry of an archive containing tens of thousands of files.
                val entry = zip.getEntry(member) ?: zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && normalMember(it.name) == member }
                if (entry != null && !entry.isDirectory) {
                    zip.getInputStream(entry).use { readMember(Item(normalMember(entry.name), entry.size), it) }
                }
            }
        } else traverse(archive) { item, data ->
            if (item.name != member) false
            else { readMember(item, data); true }
        }
        return result ?: error("归档中找不到文件：" + member)
    }

    private fun page(input: InputStream, name: String, offset: Long, maxChars: Int): String {
        InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            var skip = offset
            while (skip > 0L) {
                val count = reader.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                if (count < 0) return "成员：" + name + "；字符偏移超出文件末尾；complete=true"
                skip -= count
            }
            val out = StringBuilder()
            while (out.length < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - out.length))
                if (count < 0) break
                out.append(buffer, 0, count)
            }
            val more = reader.read() >= 0
            return buildString {
                appendLine("成员：" + name + "；start_char=" + offset + "；本页字符：" + out.length + "；complete=" + !more)
                if (more) appendLine("next_char=" + (offset + out.length) + "；继续 archive_read 才能读完本文件")
                appendLine("--- 内容开始 ---")
                append(out)
                appendLine()
                append("--- 内容结束 ---")
            }
        }
    }

    private fun isZipArchive(file: File): Boolean =
        listOf(".zip", ".jar", ".aar", ".apk", ".epub", ".docx", ".xlsx", ".pptx")
            .any(file.name.lowercase()::endsWith)

    /**
     * The index itself is stored on disk; pagination holds only one current item
     * and at most [limit] returned entries, independent of archive byte size.
     */
    private fun indexedEntries(archive: File, visit: (Item) -> Unit) {
        val owner = root.canonicalFile
        val directory = File(owner, ".dsh/archive-index")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建压缩包目录缓存" }
        require(!Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.toPath().startsWith(owner.toPath())) {
            "压缩包目录缓存位置不安全"
        }
        val name = sha256(archive.canonicalPath) + ".index"
        val index = File(directory, name)
        val identity = archiveIdentity(archive)
        fun valid(): Boolean =
            index.isFile && !Files.isSymbolicLink(index.toPath()) &&
                index.bufferedReader().use { it.readLine() == identity }
        if (!valid()) {
            val pending = File(directory, "." + name + "-" + UUID.randomUUID() + ".tmp")
            try {
                pending.bufferedWriter().use { output ->
                    output.appendLine(identity)
                    traverse(archive) { item, _ ->
                        val nameBytes = item.name.toByteArray(Charsets.UTF_8)
                        require(nameBytes.size <= 64 * 1024) { "归档成员名称过长" }
                        output.append(Base64.getEncoder().encodeToString(nameBytes))
                            .append('\t').append(item.size.toString()).append('\n')
                        false
                    }
                }
                try {
                    Files.move(pending.toPath(), index.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(pending.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                pending.delete()
            }
        }
        index.bufferedReader().use { input ->
            check(input.readLine() == identity) { "压缩包在建立目录索引后发生变化" }
            while (true) {
                if (Thread.currentThread().isInterrupted)
                    throw java.io.InterruptedIOException("压缩包枚举已取消")
                val row = input.readLine() ?: break
                val separator = row.indexOf('\t')
                require(separator > 0) { "归档目录索引已损坏" }
                val member = String(Base64.getDecoder().decode(row.substring(0, separator)), Charsets.UTF_8)
                val bytes = row.substring(separator + 1).toLong()
                visit(Item(member, bytes))
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun archiveIdentity(file: File): String {
        val attributes = Files.readAttributes(file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java)
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { input ->
            val sample = ByteArray(64 * 1024)
            val first = input.read(sample)
            if (first > 0) digest.update(sample, 0, first)
            if (input.length() > sample.size) {
                input.seek((input.length() - sample.size).coerceAtLeast(0L))
                val last = input.read(sample)
                if (last > 0) digest.update(sample, 0, last)
            }
        }
        return listOf(file.canonicalPath, attributes.size().toString(),
            attributes.lastModifiedTime().toString(), attributes.fileKey().toString(),
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }).joinToString("|")
    }

    private fun source(path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "仅接受工作区相对路径" }
        val workspace = root.canonicalFile
        val file = File(workspace, path).canonicalFile
        require(file.toPath().startsWith(workspace.toPath()) && file.isFile) { "归档不存在或已越过工作区" }
        return file
    }

    private fun normalMember(name: String): String {
        var normalized = name
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        return normalized
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("/") && !name.contains('\\') &&
            !name.contains('\u0000') && name.split('/').none { it == "." || it == ".." }

    private fun isBinaryName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "bin", "so", "o", "class", "dex", "png", "jpg", "jpeg", "gif", "webp", "pdf",
            "mp3", "mp4", "wav", "ogg", "woff", "woff2", "ttf", "otf", "jar", "zip",
            "gz", "7z", "rar", "sqlite", "db"
        )

    private fun traverse(file: File, visit: (Item, InputStream) -> Boolean) {
        val name = file.name.lowercase()
        if (listOf(".zip", ".jar", ".aar", ".apk", ".epub", ".docx", ".xlsx", ".pptx").any(name::endsWith)) {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val member = entries.nextElement()
                    if (!member.isDirectory) {
                        zip.getInputStream(member).use { if (visit(Item(normalMember(member.name), member.size), it)) return }
                    }
                }
            }
        } else if (name.endsWith(".tar") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            BufferedInputStream(
                if (name.endsWith(".tar")) file.inputStream()
                else GZIPInputStream(file.inputStream(), 32 * 1024),
                32 * 1024
            ).use { tar(it, visit) }
        } else if (name.endsWith(".gz")) {
            // Imported .tar.gz files are content-addressed as <sha>.gz. Detect the TAR
            // stream by its header instead of relying on the lost original filename.
            BufferedInputStream(GZIPInputStream(file.inputStream(), 32 * 1024), 32 * 1024).use { stream ->
                stream.mark(512)
                val head = stream.readNBytes(512)
                stream.reset()
                val tarHeader = head.size == 512 &&
                    String(head, 257, 5, StandardCharsets.US_ASCII) == "ustar"
                if (tarHeader) tar(stream, visit)
                else visit(Item(file.name.dropLast(3), -1L), stream)
            }
        } else error("当前不支持的归档格式：" + file.name)
    }

    private fun tar(input: InputStream, visit: (Item, InputStream) -> Boolean) {
        val header = ByteArray(512)
        var extendedName: String? = null
        while (true) {
            if (!headerBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val size = zeroString(header, 124, 12).trim().trim('\u0000').toLongOrNull(8)
                ?: error("TAR 条目大小格式损坏")
            require(size >= 0L) { "TAR 条目大小错误" }
            val kind = header[156].toInt().toChar()
            val base = zeroString(header, 0, 100)
            val prefix = zeroString(header, 345, 155)
            val name = extendedName ?: if (prefix.isEmpty()) base else prefix + "/" + base
            val body = LimitedInput(input, size)
            if (kind == 'x' || kind == 'L') {
                require(size <= 1_048_576L) { "TAR 扩展头过大" }
                val bytes = body.readNBytes(size.toInt())
                extendedName = if (kind == 'L') String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                    else Regex("""(?:^|\n)\d+ path=([^\n]+)""").find(String(bytes, StandardCharsets.UTF_8))?.groupValues?.get(1)
            } else {
                extendedName = null
                if (kind == '0' || kind == '\u0000') {
                    if (visit(Item(normalMember(name), size), body)) return
                }
            }
            body.drain()
            skipBytes(input, (512L - size % 512L) % 512L)
        }
    }

    private fun zeroString(buffer: ByteArray, pos: Int, length: Int): String {
        val end = (pos until pos + length).firstOrNull { buffer[it] == 0.toByte() } ?: pos + length
        return String(buffer, pos, end - pos, StandardCharsets.UTF_8)
    }

    private fun headerBlock(input: InputStream, header: ByteArray): Boolean {
        var count = 0
        while (count < header.size) {
            val n = input.read(header, count, header.size - count)
            if (n < 0) {
                require(count == 0) { "TAR 标头不完整" }
                return false
            }
            count += n
        }
        return true
    }

    private fun skipBytes(input: InputStream, length: Long) {
        var rest = length
        val buffer = ByteArray(32 * 1024)
        while (rest > 0L) {
            val n = input.read(buffer, 0, minOf(rest, buffer.size.toLong()).toInt())
            require(n > 0) { "归档在读取过程中被截断" }
            rest -= n
        }
    }

    private class LimitedInput(private val upstream: InputStream, size: Long) : InputStream() {
        private var left = size
        override fun read(): Int {
            if (left == 0L) return -1
            val n = upstream.read()
            require(n >= 0) { "TAR 成员内容被截断" }
            left--
            return n
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left == 0L) return -1
            val n = upstream.read(b, off, minOf(left, len.toLong()).toInt())
            require(n >= 0) { "TAR 成员内容被截断" }
            if (n > 0) left -= n
            return n
        }
        override fun close() { /* The archive traversal owns the underlying stream. */ }
        fun drain() {
            val bytes = ByteArray(32 * 1024)
            while (left > 0L) {
                val n = read(bytes)
                require(n > 0) { "TAR 成员被截断" }
            }
        }
    }
}
