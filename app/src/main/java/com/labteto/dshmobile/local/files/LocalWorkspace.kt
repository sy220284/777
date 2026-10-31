package com.labteto.dshmobile.local.files

import com.labteto.dshmobile.local.tools.LocalSandboxBoundary
import com.labteto.dshmobile.local.tools.LocalWorkspaceFile
import com.labteto.dshmobile.local.tools.LocalWorkspaceFilePreview
import java.io.File
import com.labteto.dshmobile.local.io.readBoundedLine
import com.labteto.dshmobile.observability.AppLog
import java.io.FileOutputStream
import java.io.Reader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class LocalFileObservationCache(
    private val maxEntries: Int = 2_048,
) {
    private val entries = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    init {
        require(maxEntries > 0) { "文件观察缓存容量必须大于 0" }
    }

    fun get(path: String): String? = synchronized(entries) { entries[path] }

    fun put(path: String, fingerprint: String) {
        synchronized(entries) { entries[path] = fingerprint }
    }

    fun remove(path: String) {
        synchronized(entries) { entries.remove(path) }
    }

    internal fun size(): Int = synchronized(entries) { entries.size }
}

private const val MAX_MODEL_SKILL_LIST = 128
private const val MAX_SKILL_BYTES = 128 * 1024

/** One immediate child of a workspace directory, never a recursive search result. */
data class LocalWorkspaceDirectoryEntry(
    val path: String,
    val isDirectory: Boolean,
    val bytes: Long,
    val modifiedAt: Long,
)

/** One immutable slice of a directory name snapshot; no file contents are read. */
data class LocalWorkspaceDirectoryPage(
    val entries: List<LocalWorkspaceDirectoryEntry>,
    val nextOffset: Int?,
)

/** Progressive path/name search; result rows hold metadata only, never file contents. */
data class LocalWorkspaceSearchPage(
    val files: List<LocalWorkspaceFile>,
    val nextOffset: Int?,
)

/** Sandboxed filesystem and shell provider for the on-device Harness. */
class LocalWorkspace(
    private val root: File,
    private val extraSearchPaths: () -> List<File> = { emptyList() },
    private val environmentProvider: () -> Map<String, String> = { emptyMap() },
    private val shellExecutable: String = "/system/bin/sh",
    /**
     * Sandbox boundary drawn at firmware. Defaults to workspace-only, matching the pre-existing
     * behaviour; callers opt into shared-storage roots explicitly.
     */
    private val boundary: LocalSandboxBoundary = LocalSandboxBoundary.workspaceOnly(root),
) {
    private val canonicalRoot = root.canonicalFile
    private val observations = LocalFileObservationCache()
    private val textIndex = LocalTextLineIndex(canonicalRoot)
    private val fileWriteLock = Any()
    // Retain only the small traversal frontier between consecutive tool pages. On a
    // cache miss (process restart, different query or competing caller), numeric cursors
    // still work by replaying the deterministic walk.
    private class ScanIterator(
        private val backing: Iterator<IndexedValue<File>>,
    ) : Iterator<IndexedValue<File>> {
        private var pending: IndexedValue<File>? = null

        override fun hasNext(): Boolean = pending != null || backing.hasNext()

        override fun next(): IndexedValue<File> {
            val first = pending
            if (first != null) {
                pending = null
                return first
            }
            return backing.next()
        }

        fun defer(value: IndexedValue<File>) {
            check(pending == null)
            pending = value
        }
    }

    private data class ScanResume(val cursor: Int, val iterator: ScanIterator)
    private val scanResumes = object : LinkedHashMap<String, ScanResume>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScanResume>?): Boolean =
            size > 8
    }

    private fun scanPageIterator(
        directory: File,
        maxDepth: Int,
        key: String,
        cursor: Int,
    ): ScanIterator {
        val previous = synchronized(scanResumes) {
            scanResumes.remove(key)?.takeIf { it.cursor == cursor }
        }
        return previous?.iterator ?: ScanIterator(safeWalk(
            directory, maxDepth = maxDepth, maxVisited = Int.MAX_VALUE, maxMillis = Long.MAX_VALUE,
        ).withIndex().iterator())
    }

    private fun saveScanResume(
        key: String,
        entry: Int,
        file: File,
        iterator: ScanIterator,
    ) {
        iterator.defer(IndexedValue(entry, file))
        synchronized(scanResumes) {
            scanResumes[key] = ScanResume(entry, iterator)
        }
    }

    init {
        canonicalRoot.mkdirs()
    }

    /** Stable app-private workspace root. */
    val path: String get() = canonicalRoot.absolutePath

    /** A separate instance keeps observations and traversal cursors bound to this physical root. */
    internal fun atRoot(directory: File): LocalWorkspace = LocalWorkspace(
        root = directory, extraSearchPaths = extraSearchPaths, environmentProvider = environmentProvider,
        shellExecutable = shellExecutable, boundary = LocalSandboxBoundary.workspaceOnly(directory),
    )

    /** Read a UTF-8 text range, bounded to keep one result out of the model's entire context. */
    fun read(
        relativePath: String,
        startLine: Int = 1,
        endLine: Int = startLine + 399,
        startChar: Int = 0,
    ): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        require(startChar >= 0) { "行内字符偏移不能为负数" }
        // Only editable files need a content observation. Large read-only pages must not
        // hash the entire source twice before returning a small excerpt.
        val editable = file.length() <= 8L * 1024 * 1024
        val attributes = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        val version = if (editable) null else textIndex.checkpoint(file, startLine.coerceAtLeast(1))
        val before = if (editable) fingerprint(file) else requireNotNull(version).digest
        val page = readLocalTextPage(
            file, startLine.coerceAtLeast(1), endLine.coerceAtLeast(startLine.coerceAtLeast(1)), startChar,
            checkpointLine = version?.line ?: 1, checkpointByte = version?.offset ?: 0L,
        )
        val after = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        require(attributes.size() == after.size() && attributes.lastModifiedTime() == after.lastModifiedTime() &&
            attributes.fileKey() == after.fileKey()) { "文件在读取过程中发生变化，请重新读取：$relativePath" }
        if (editable) require(before == fingerprint(file)) {
            "文件在读取过程中发生变化，请重新读取：$relativePath"
        }
        // The line-index SHA tracks this exact version; edit verifies full SHA again.
        observations.put(file.path, before)
        return page
    }

    /** Replace one UTF-8 file, creating its parent directories. */
    fun write(relativePath: String, content: String): String {
        val file = resolveForWrite(relativePath)
        val bytes = LocalLargeFileIO.writeText(file, content)
        observations.remove(file.path)
        return "已写入 " + relativePath + "（" + bytes + " 字节）"
    }

    /** Stream arbitrary-sized binary artifacts through one bounded buffer. */
    fun writeFromStream(relativePath: String, source: java.io.InputStream, onProgress: (Long) -> Unit = {}): Long {
        val file = resolveForWrite(relativePath)
        val bytes = LocalLargeFileIO.writeStream(file, source, onProgress)
        observations.remove(file.path)
        return bytes
    }

    /** Multi-request, restart-resumable byte-offset write for content generated by tools. */
    fun writeChunk(relativePath: String, content: String, offset: Long, finish: Boolean): String {
        val file = resolveForWrite(relativePath)
        val next = synchronized(fileWriteLock) {
            LocalLargeFileIO.writeChunk(file, content, offset, finish)
        }
        if (finish) observations.remove(file.path)
        return "分块写入 " + relativePath + "；next_byte=" + next +
            if (finish) "；complete=true" else "；complete=false（继续使用 offset=next_byte）"
    }

    /** Store tool-owned artifacts (for example large web responses) without a second approval prompt. */
    fun writeToolArtifact(relativePath: String, content: String): String {
        val file = resolveForWrite(relativePath)
        LocalLargeFileIO.writeText(file, content)
        // Keep the last observed version: edit must report stale bytes (rather than
        // a missing observation) when a tool artifact overwrote the read file.
        return file.relativeTo(canonicalRoot).invariantSeparatorsPath
    }

    /** Resolve a tool-owned output path without letting callers escape the sandbox. */
    fun toolOutputFile(relativePath: String): File {
        val file = resolveForWrite(relativePath)
        file.parentFile?.mkdirs()
        return file
    }

    /** Read raw UTF-8 content for structured parsers. */
    fun readRaw(relativePath: String): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        // Structured parsers require one materialized String. Admission tracks *available*
        // heap, not an arbitrary file-size ceiling; larger data uses read/readRawPage.
        val vm = Runtime.getRuntime()
        val available = (vm.maxMemory() - vm.totalMemory() + vm.freeMemory()).coerceAtLeast(0L)
        require(file.length() <= available / 8L) {
            "当前可用内存不足以一次解析完整文件，请使用 read 或 readRawPage 分段读取：" + relativePath
        }
        val before = fingerprint(file)
        val content = file.readText()
        val after = fingerprint(file)
        require(before == after) { "文件在读取过程中发生变化，请重新读取：$relativePath" }
        observations.put(file.path, after)
        return content

    }

    /** Binary-safe paged raw read. The file size never determines allocation size. */
    fun readRawPage(relativePath: String, offset: Long, maxBytes: Int = 64 * 1024): ByteArray {
        require(offset >= 0L && maxBytes in 1..1024 * 1024) { "分页读取参数无效" }
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：" + relativePath }
        java.io.RandomAccessFile(file, "r").use { source ->
            require(offset <= source.length()) { "字节偏移超出文件末尾" }
            source.seek(offset)
            val bytes = ByteArray(minOf(maxBytes.toLong(), source.length() - offset).toInt())
            source.readFully(bytes)
            return bytes
        }
    }

    /** Verify that the model observed the current file version before asking the user to approve an edit. */
    fun requireFreshObservation(relativePath: String): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        val observed = observations.get(file.path)
            ?: error("编辑前必须先读取文件：$relativePath。write 创建或覆盖文件不算读取；请先用 read 读取包含待替换内容的目标区域，再调用 edit")
        require(observed == fingerprint(file)) {
            "文件在读取后已发生变化，请重新读取再编辑：$relativePath"
        }
        return observed
    }
    /** Replace one unique literal after the caller has observed the file. */
    fun edit(relativePath: String, oldText: String, newText: String): String {
        require(oldText.isNotEmpty()) { "待替换内容不能为空" }
        val file = resolveForWrite(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        val observed = requireFreshObservation(relativePath)
        LocalLargeFileIO.replaceUnique(file, oldText, newText) {
            require(observed == fingerprint(file)) {
                "文件在准备编辑时发生变化，请重新读取再编辑：" + relativePath
            }
        }
        observations.put(file.path, fingerprint(file))
        return "已编辑 " + relativePath
    }

    /** Glob-style file discovery, paged instead of silently cutting off a large workspace. */
    fun glob(pattern: String, relativePath: String = ".", cursor: Int = 0): String {
        require(pattern.isNotBlank()) { "匹配模式不能为空" }
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "目录不存在：$relativePath" }
        val matcher = globRegex(pattern.replace('\\', '/'))
        return discoverPage(directory, MAX_SCAN_DEPTH, cursor, "glob", "未找到匹配文件", pattern) { file ->
            file.takeIf { it.isFile && isInsideWorkspace(it) }
                ?.relativeTo(directory)?.invariantSeparatorsPath
                ?.takeIf(matcher::matches)
        }
    }

    /** List descendants with a cursor that preserves the full discoverable set. */
    fun list(relativePath: String = ".", depth: Int = 3, cursor: Int = 0): String {
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "目录不存在：$relativePath" }
        return discoverPage(directory, depth.coerceIn(1, 8), cursor, "list_files", "目录为空", "") { file ->
            file.takeIf { it != directory && isInsideWorkspace(it) }?.let {
                val suffix = if (it.isDirectory) "/" else " (${it.length()} B)"
                displayPath(it) + suffix
            }
        }
    }

    private fun discoverPage(
        directory: File,
        depth: Int,
        cursor: Int,
        toolName: String,
        emptyMessage: String,
        pattern: String,
        project: (File) -> String?,
    ): String {
        require(cursor >= 0) { "目录扫描游标不能为负数" }
        val rows = mutableListOf<String>()
        var pageEntries = 0
        var cursorFound = cursor == 0
        var nextCursor: Int? = null
        var started = 0L
        val scanKey = listOf(toolName, directory.path, depth.toString(), pattern).joinToString("\u0000")
        val iterator = scanPageIterator(directory, depth, scanKey, cursor)
        while (iterator.hasNext()) {
            val (entry, file) = iterator.next()
            if (entry < cursor) continue
            cursorFound = true
            // Page time begins when the continuation point is reached. Otherwise every
            // later page can immediately expire while replaying the already-seen prefix.
            if (started == 0L) started = System.nanoTime()
            if (pageEntries >= MAX_SCAN_ENTRIES || rows.size >= MAX_LIST_ROWS ||
                (System.nanoTime() - started) / 1_000_000L >= MAX_SCAN_MILLIS) {
                nextCursor = entry
                saveScanResume(scanKey, entry, file, iterator)
                break
            }
            pageEntries++
            project(file)?.let(rows::add)
        }
        require(cursorFound) { "目录扫描游标已失效，请从头查询" }
        return buildString {
            append(if (rows.isEmpty()) {
                if (nextCursor == null) emptyMessage else "本页没有匹配文件"
            } else rows.joinToString("\n"))
            if (nextCursor != null) {
                append("\n[目录扫描未完：再次调用 $toolName，保持路径、匹配条件与深度不变，")
                append("传入 cursor=$nextCursor 继续；目录发生增删时请从头扫描]")
            }
        }
    }

    /**
     * Bounded, resumable recursive search. Cursor is (traversal entry, last consumed line).
     * As traversal order is deterministic, repeat with the same query/path/regex to resume.
     * Restart from the beginning after changing the workspace tree.
     */
    fun search(query: String, relativePath: String = ".", regex: Boolean = false, cursor: String? = null): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val expression = if (regex) runCatching { Regex(query, RegexOption.IGNORE_CASE) }
            .getOrElse { throw IllegalArgumentException("正则表达式无效：${it.message}") }
        else null
        val directory = resolve(relativePath)
        require(directory.exists()) { "路径不存在：$relativePath" }
        val parts = cursor?.split(':')
        require(parts == null || parts.size == 2) { "搜索游标无效，请从头搜索" }
        val resumeEntry = parts?.get(0)?.toIntOrNull() ?: 0
        val resumeLine = parts?.get(1)?.toIntOrNull() ?: 0
        require(resumeEntry >= 0 && resumeLine >= 0 &&
            (parts == null || parts.all { it.toIntOrNull() != null })) {
            "搜索游标无效，请从头搜索"
        }
        val scanKey = listOf("search", directory.path, regex.toString(), query).joinToString("\u0000")
        val iterator = if (directory.isFile) {
            scanPageIterator(directory, 0, scanKey, resumeEntry)
        } else scanPageIterator(directory, MAX_SCAN_DEPTH, scanKey, resumeEntry)
        val matches = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var outputChars = 0
        var pageEntries = 0
        var resumeFound = cursor == null
        var nextCursor: String? = null
        var startedAt = 0L
        while (iterator.hasNext()) {
            val (entry, file) = iterator.next()
            if (entry < resumeEntry) continue
            resumeFound = true
            // Reaching an existing cursor can require replaying the directory prefix.
            // Give the new page its full processing budget once its cursor is reached.
            if (startedAt == 0L) startedAt = System.nanoTime()
            if (pageEntries >= MAX_SCAN_ENTRIES ||
                (System.nanoTime() - startedAt) / 1_000_000L >= MAX_SCAN_MILLIS) {
                nextCursor = "$entry:${if (entry == resumeEntry) resumeLine else 0}"
                saveScanResume(scanKey, entry, file, iterator)
                break
            }
            pageEntries++
            if (!file.isFile || !isInsideWorkspace(file)) continue
            try {
                file.bufferedReader().use { reader ->
                    var lineNumber = 0
                    // Resume-line replay does not consume this page's matching budget.
                    // Otherwise a late cursor in a long file can repeatedly time out
                    // before reading a single new line.
                    if (entry == resumeEntry && resumeLine > 0) {
                        while (lineNumber < resumeLine &&
                            readBoundedLine(reader, MAX_SEARCH_LINE_CHARS) != null) {
                            lineNumber++
                        }
                        startedAt = System.nanoTime()
                    }
                    while (true) {
                        if (matches.size >= MAX_SEARCH_ROWS || outputChars >= MAX_SEARCH_CHARS ||
                            (System.nanoTime() - startedAt) / 1_000_000L >= MAX_SCAN_MILLIS) {
                            nextCursor = "$entry:$lineNumber"
                            break
                        }
                        val line = readBoundedLine(reader, MAX_SEARCH_LINE_CHARS) ?: break
                        lineNumber++
                        val matchStart = expression?.find(line)?.range?.first
                            ?: if (expression == null) line.indexOf(query, ignoreCase = true) else -1
                        if (matchStart >= 0) {
                            val previewStart = (matchStart - 100).coerceAtLeast(0)
                            val row = "${displayPath(file)}:$lineNumber: " +
                                (if (previewStart > 0) "…" else "") +
                                line.substring(previewStart, minOf(line.length, previewStart + MAX_SEARCH_PREVIEW_CHARS)) +
                                if (line.length > previewStart + MAX_SEARCH_PREVIEW_CHARS) "…" else ""
                            matches += row
                            outputChars += row.length
                        }
                    }
                }
            } catch (error: Exception) {
                warnings += "文件 ${displayPath(file)} 无法完整检索：${error.message.orEmpty().take(180)}"
            }
            if (nextCursor != null) {
                saveScanResume(scanKey, entry, file, iterator)
                break
            }
        }
        require(resumeFound) { "搜索游标已经失效，工作区可能发生变化；请从头搜索" }
        return buildString {
            append(if (matches.isEmpty()) {
                if (nextCursor == null && warnings.isEmpty()) "未找到匹配内容" else "本页未找到匹配内容"
            } else matches.joinToString("\n"))
            if (warnings.isNotEmpty()) append("\n[部分文件读取失败：${warnings.take(4).joinToString("；")}；共 ${warnings.size} 项]")
            if (nextCursor != null) {
                append("\n[搜索未完成：再次调用 grep，保持 query/path/regex 不变并传入 cursor=\"")
                append(nextCursor)
                append("\" 继续；如文件发生增删，请从头搜索]")
            }
        }
    }

    private data class DirectoryNameSnapshot(val modifiedAt: Long, val names: List<String>)
    private val directoryNameSnapshots = object : LinkedHashMap<String, DirectoryNameSnapshot>(12, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DirectoryNameSnapshot>?): Boolean =
            size > 12
    }

    /** Index names once and inspect only the requested slice; existing files are never opened. */
    fun directoryPage(
        relativePath: String = ".",
        offset: Int = 0,
        pageSize: Int = 96,
    ): LocalWorkspaceDirectoryPage {
        require(offset >= 0 && pageSize in 1..256) { "文件目录分页参数不合法" }
        val directory = resolve(relativePath)
        require(directory.toPath().startsWith(canonicalRoot.toPath())) { "目录不属于工作区" }
        if (!directory.exists()) return LocalWorkspaceDirectoryPage(emptyList(), null)
        require(directory.isDirectory) { "路径不是文件夹：${relativePath}" }
        val key = directory.path
        val modified = directory.lastModified()
        val names = synchronized(directoryNameSnapshots) {
            val snapshot = directoryNameSnapshots[key]?.takeIf { offset > 0 || it.modifiedAt == modified }
            if (snapshot != null) snapshot.names else {
                require(offset == 0) { "目录内容已更新，请重新打开当前目录" }
                val refreshed = directory.list()?.sorted() ?: error("文件夹读取失败：${relativePath}")
                directoryNameSnapshots[key] = DirectoryNameSnapshot(modified, refreshed)
                refreshed
            }
        }
        require(offset <= names.size) { "目录页码已失效，请刷新" }
        val end = minOf(names.size, offset + pageSize)
        val children = names.subList(offset, end).mapNotNull { name ->
            val file = File(directory, name)
            if (Files.isSymbolicLink(file.toPath()) || !boundary.isWorkspace(file)) return@mapNotNull null
            val isDirectory = file.isDirectory
            if (!isDirectory && !file.isFile) return@mapNotNull null
            LocalWorkspaceDirectoryEntry(
                path = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
                isDirectory = isDirectory,
                bytes = if (isDirectory) 0L else file.length(),
                modifiedAt = file.lastModified(),
            )
        }
        return LocalWorkspaceDirectoryPage(children, end.takeIf { it < names.size })
    }

    /** Inspect one path referenced by a conversation without scanning unrelated files. */
    fun workspaceFileAt(relativePath: String): LocalWorkspaceFile? {
        if (relativePath.isBlank() || relativePath.startsWith('/')) return null
        val original = File(canonicalRoot, relativePath).toPath().normalize()
        if (!original.startsWith(canonicalRoot.toPath())) return null
        var current = canonicalRoot.toPath()
        for (part in canonicalRoot.toPath().relativize(original)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return null
        }
        val file = resolve(relativePath)
        if (!file.isFile || !boundary.isWorkspace(file)) return null
        return LocalWorkspaceFile(
            path = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
            bytes = file.length(),
            modifiedAt = file.lastModified(),
        )
    }

    /** Compatibility listing for internal callers; not used by the scrolling file browser. */
    fun directoryEntries(relativePath: String = "."): List<LocalWorkspaceDirectoryEntry> {
        val all = ArrayList<LocalWorkspaceDirectoryEntry>()
        var next: Int? = 0
        while (next != null) {
            val page = directoryPage(relativePath, next)
            all.addAll(page.entries)
            next = page.nextOffset
        }
        return all
    }

    private class SearchCursor(
        val iterator: Iterator<File>,
        var nextPage: Int = 0,
        var emitted: Int = 0,
        var exhausted: Boolean = false,
    )
    private val searchCursors = object : LinkedHashMap<String, SearchCursor>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchCursor>?): Boolean =
            size > 4
    }

    /**
     * A cursor advances once per scan batch, even when the batch finds zero matches.
     * This is essential for sparse searches: an empty partial page must not rewind to page zero.
     */
    fun searchWorkspacePage(query: String, offset: Int = 0, pageSize: Int = 96): LocalWorkspaceSearchPage {
        val needle = query.trim()
        require(needle.isNotEmpty() && offset >= 0 && pageSize in 1..256) { "搜索分页参数不合法" }
        return synchronized(searchCursors) {
            val scan = if (offset == 0) {
                SearchCursor(safeWalk(canonicalRoot, maxVisited = 50_000,
                    maxMillis = Long.MAX_VALUE).iterator()).also { searchCursors[needle] = it }
            } else searchCursors[needle] ?: error("搜索会话已过期，请重新搜索")
            require(offset == scan.nextPage) { "搜索游标已经失效，请重新搜索" }
            val matched = ArrayList<LocalWorkspaceFile>(pageSize)
            var visited = 0
            while (matched.size < pageSize && !scan.exhausted &&
                visited < 2_048 && scan.emitted < 10_000
            ) {
                if (!scan.iterator.hasNext()) {
                    scan.exhausted = true
                    break
                }
                val file = scan.iterator.next()
                visited++
                if (!file.isFile || !isInsideWorkspace(file)) continue
                val path = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                if (path.contains(needle, ignoreCase = true)) {
                    matched += LocalWorkspaceFile(path, file.length(), file.lastModified())
                    scan.emitted++
                }
            }
            scan.nextPage++
            LocalWorkspaceSearchPage(
                files = matched,
                nextOffset = scan.nextPage.takeIf { !scan.exhausted && scan.emitted < 10_000 },
            )
        }
    }

    /** Search by path/name without truncating before matching files are considered. */
    fun searchWorkspaceFileNames(query: String, limit: Int = 500): List<LocalWorkspaceFile> {
        val needle = query.trim()
        require(needle.isNotBlank()) { "搜索关键词不能为空" }
        return safeWalk(canonicalRoot, maxVisited = 50_000, maxMillis = 5_000L)
            .filter { it.isFile && isInsideWorkspace(it) }
            .filter { it.relativeTo(canonicalRoot).invariantSeparatorsPath.contains(needle, ignoreCase = true) }
            .take(limit.coerceIn(1, 500))
            .map { file ->
                LocalWorkspaceFile(
                    path = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
                    bytes = file.length(),
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedBy(LocalWorkspaceFile::path)
            .toList()
    }

    /** Snapshot real files for explicit global search without exposing raw File handles. */
    fun files(limit: Int = 2_000): List<LocalWorkspaceFile> =
        safeWalk(canonicalRoot)
            .filter { it.isFile && isInsideWorkspace(it) }
            .take(limit.coerceIn(1, 10_000))
            .map { file ->
                LocalWorkspaceFile(
                    path = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
                    bytes = file.length(),
                    modifiedAt = file.lastModified(),
                )
            }
            .sortedBy(LocalWorkspaceFile::path)
            .toList()

    /** Random-access UTF-8 windows; nextOffset navigates an arbitrarily large file. */
    fun preview(relativePath: String, maxBytes: Int = 64 * 1024, offset: Long = 0L): LocalWorkspaceFilePreview {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：" + relativePath }
        val length = file.length()
        require(offset in 0..length) { "预览字节偏移已失效" }
        val pageBytes = maxBytes.coerceIn(4, 256 * 1024)
        var binary = relativePath.substringAfterLast('.', "").lowercase() in BINARY_PREVIEW_EXTENSIONS
        var content: String? = null
        var used = 0
        java.io.RandomAccessFile(file, "r").use { reader ->
            if (!binary) {
                val sample = ByteArray(minOf(length, 8192L).toInt())
                reader.readFully(sample)
                binary = sample.any { it == 0.toByte() }
            }
            if (!binary) {
                reader.seek(offset)
                val data = ByteArray(minOf(pageBytes.toLong(), length - offset).toInt())
                reader.readFully(data)
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                for (trim in 0..minOf(3, data.size)) {
                    decoder.reset()
                    val decoded = runCatching {
                        decoder.decode(java.nio.ByteBuffer.wrap(data, 0, data.size - trim)).toString()
                    }.getOrNull()
                    if (decoded != null) {
                        content = decoded
                        used = data.size - trim
                        break
                    }
                }
                if (content == null) {
                    content = String(data, Charsets.UTF_8)
                    used = data.size
                }
            }
        }
        val next = (offset + used).takeIf { !binary && it < length }
        return LocalWorkspaceFilePreview(
            file = LocalWorkspaceFile(displayPath(file), length, file.lastModified()),
            text = content,
            truncated = if (binary) length > pageBytes else next != null,
            offset = offset,
            nextOffset = next,
        )
    }

    /**
     * Stream a file from the *app-owned workspace* into a user-selected SAF destination.
     *
     * The general tool sandbox also permits user-selected shared roots. An export operation
     * deliberately narrows this to [canonicalRoot] so a UI path cannot reveal arbitrary
     * shared files, session metadata, or an external symlink target.
     */
    fun exportFileForUi(relativePath: String, output: java.io.OutputStream): Long {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) {
            "只能导出工作区内的相对文件路径"
        }
        val source = File(canonicalRoot, relativePath)
        val canonical = source.canonicalFile
        require(canonical.path.startsWith(canonicalRoot.path + File.separator) &&
            canonical.isFile) { "拒绝导出工作区外文件或不存在的文件" }

        // Reject symlinked paths even if the final target remains inside the workspace.
        val relative = canonicalRoot.toPath().relativize(source.toPath().normalize())
        var current = canonicalRoot.toPath()
        for (component in relative) {
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "拒绝通过符号链接导出文件" }
        }

        var total = 0L
        java.nio.channels.Channels.newInputStream(Files.newByteChannel(
            source.toPath(), setOf(java.nio.file.StandardOpenOption.READ,
                java.nio.file.LinkOption.NOFOLLOW_LINKS),
        )).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return total
    }

    /**
     * Execute Android's system shell with an inactivity timeout that resets on output.
     *
     * Enforcement here is the kernel's, not this class's. `directory(canonicalRoot)` sets the
     * working directory only — it is not an access restriction, and a command can `cd /` out of it.
     * What actually keeps firmware and other apps' private data out of reach is the mount table
     * (`/system` and friends are read-only) plus the untrusted-app SELinux domain. The command
     * policy in LocalToolPolicy is a best-effort second line: it withholds the prompt for commands
     * that name a firmware location, but static inspection of shell text is not a guarantee.
     */
    suspend fun shell(
        command: String,
        timeoutSeconds: Int,
        onProgress: ((String) -> Unit)? = null,
        throwOnFailure: Boolean = false,
    ): String {
        val result = shellResult(command, timeoutSeconds, onProgress)
        if (throwOnFailure && result.isError) error("[shell][${result.errorCode}] ${result.content}")
        return result.content
    }

    suspend fun shellResult(
        command: String,
        timeoutSeconds: Int,
        onProgress: ((String) -> Unit)? = null,
    ): com.labteto.dshmobile.harness.tools.ToolResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        // Retain stdout and stderr as separate evidence. Shell 126/127 cannot be
        // diagnosed from their numeric exit status without the command's stderr.
        val builder = ProcessBuilder(shellExecutable, "-c", command)
            .directory(canonicalRoot)
            .redirectErrorStream(false)
        builder.environment().apply {
            putAll(environmentProvider())
            val inheritedPath = get("PATH").orEmpty()
            val runtimePath = extraSearchPaths()
                .filter(File::isDirectory)
                .joinToString(File.pathSeparator) { it.absolutePath }
            if (runtimePath.isNotBlank()) {
                put(
                    "PATH",
                    listOf(runtimePath, inheritedPath)
                        .filter(String::isNotBlank)
                        .joinToString(File.pathSeparator),
                )
            }
        }
        val result = com.labteto.dshmobile.runtime.executeManagedProcess(
            builder = com.labteto.dshmobile.runtime.secureIsolatedProcessBuilder(builder, extraSearchPaths(), environmentProvider()),
            timeoutMillis = timeoutSeconds.coerceIn(1, MAX_SHELL_TIMEOUT_SECONDS) * 1000L,
            maxChars = MAX_SHELL_CHARS,
            onProgress = onProgress,
            progressResetsTimeout = true,
        )
        val diagnosis = when {
            result.timedOut -> "命令长时间无 stdout/stderr 进度，进程组已终止；写入操作是否生效需核实。长时间静默的构建请使用后台模式、增加进度输出或适当设置空闲超时"
            result.exitCode == 126 -> "shell 找到了命令但无法执行；检查文件执行权限、解释器、挂载选项和 Android SELinux 限制"
            result.exitCode == 127 -> "shell 无法定位命令；检查实际命令拼写、PATH、内置运行时状态和脚本解释器"
            else -> null
        }
        val stdout = result.stdout.take(MAX_SHELL_CHARS)
        val stderr = result.stderr.take(MAX_SHELL_CHARS)
        val content = buildString {
            appendLine("退出码：${result.exitCode}")
            appendLine("超时：${result.timedOut}")
            if (stdout.isNotBlank()) appendLine(stdout)
            if (stderr.isNotBlank()) {
                appendLine("标准错误：")
                appendLine(stderr)
            }
            diagnosis?.let {
                appendLine("诊断：$it")
            }
        }.trimEnd()
        com.labteto.dshmobile.harness.tools.ToolResult(
            content = content,
            isError = result.timedOut || result.exitCode != 0,
            errorCode = if (result.timedOut) "TOOL_TIMEOUT" else
                result.exitCode.takeIf { it != 0 }?.let { "PROCESS_EXIT_$it" },
            processExitCode = result.exitCode.takeUnless { result.timedOut },
            recoveryHint = diagnosis,
        )
    }

    /** Installed workspace skill names. */
    fun skills(): List<String> {
        val directory = resolve(".dsh/skills")
        return directory.listFiles().orEmpty()
            .filter { child ->
                child.isDirectory &&
                    !Files.isSymbolicLink(child.toPath()) &&
                    File(child, "SKILL.md").isFile
            }
            .map { it.name }
            .sorted()
    }

    /** Full rules, with a byte budget independent of the generic file-preview line limit. */
    fun readSkill(name: String): String {
        val file = skillFile(name)
        require(file.length() <= MAX_SKILL_BYTES) { "技能规则超过 128 KB，请精简后重新加载" }
        val before = fingerprint(file)
        val bytes = file.inputStream().use { it.readNBytes(MAX_SKILL_BYTES + 1) }
        require(bytes.size <= MAX_SKILL_BYTES) { "技能规则超过 128 KB，请精简后重新加载" }
        val content = bytes.toString(Charsets.UTF_8)
        val after = fingerprint(file)
        require(before == after) { "技能在读取过程中发生变化，请重新加载" }
        observations.put(file.path, after)
        return content
    }

    /** Catalogs only need front matter; one unreadable skill cannot disable all other skills. */
    internal fun skillMetadata(name: String): LocalSkillMetadata {
        val file = skillFile(name)
        require(file.length() <= MAX_SKILL_BYTES) { "技能规则超过 128 KB" }
        val header = file.bufferedReader().use { reader ->
            buildList {
                repeat(96) {
                    val line = readBoundedLine(reader, 1_024) ?: return@buildList
                    add(line)
                    if (size == 1 && line.trim() != "---") return@buildList
                    if (size > 1 && line.trim() == "---") return@buildList
                }
            }.joinToString("\n")
        }
        return parseLocalSkillMetadata(name, header)
    }

    private fun skillFile(name: String): File {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized != "." && normalized != ".." &&
            File.separatorChar !in normalized && '/' !in normalized && '\\' !in normalized) {
            "技能名称只能是 .dsh/skills 下的直接子目录名"
        }
        return resolve(".dsh/skills/$normalized/SKILL.md").also {
            require(it.isFile) { "技能文件不存在：$normalized" }
        }
    }

    /** A page of the full eligible catalog; never filter *after* taking the first N directories. */
    fun modelSkillCatalog(
        offset: Int = 0,
        limit: Int = MAX_MODEL_SKILL_LIST,
        query: String = "",
        maxChars: Int = Int.MAX_VALUE,
    ): String {
        val eligible = skills().mapNotNull { name ->
            try {
                skillMetadata(name)
            } catch (error: Exception) {
                AppLog.warn("LocalSkills", "技能目录条目无法读取：$name", error)
                null
            }
        }.filter(LocalSkillMetadata::modelInvocable)
        if (eligible.isEmpty()) return "未安装可由模型调用的技能"

        val relevance = query.lowercase().split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 2 }.take(32)
        val ordered = if (relevance.isEmpty()) eligible else eligible.sortedWith(
            compareByDescending<LocalSkillMetadata> { skill ->
                val searchable = "${skill.name} ${skill.description} ${skill.whenToUse.orEmpty()}".lowercase()
                relevance.count(searchable::contains)
            }.thenBy(LocalSkillMetadata::name),
        )
        val from = offset.coerceAtLeast(0)
        if (from >= ordered.size) return "技能目录已到末尾（共 ${ordered.size} 项）"
        val rows = mutableListOf<String>()
        var usedChars = 0
        val pageLimit = limit.coerceIn(1, MAX_MODEL_SKILL_LIST)
        val charBudget = maxChars.coerceAtLeast(160)
        for (skill in ordered.drop(from).take(pageLimit)) {
            val description = skill.description.ifBlank { "无描述" }
            val guidance = skill.whenToUse?.let { "；适用：$it" }.orEmpty()
            val row = "${skill.name}：$description$guidance"
            if (rows.isNotEmpty() && usedChars + row.length + 1 > charBudget) break
            if (row.length > charBudget) {
                rows += row.take(charBudget - 1) + "…"
                break
            }
            rows += row
            usedChars += row.length + 1
        }
        val next = from + rows.size
        return buildString {
            append(rows.joinToString("\n"))
            if (next < ordered.size) {
                append("\n[目录未完：还有 ${ordered.size - next} 项；调用 skill(offset=$next")
                if (query.isNotBlank()) append(", query=${query.take(80)}")
                append(") 继续查找；也可填写 name 精确读取]")
            }
        }
    }

    /** Model tool access must respect user-only metadata; direct user access remains unchanged. */
    fun readModelSkill(name: String): String {
        val content = readSkill(name)
        require(parseLocalSkillMetadata(name.trim(), content).modelInvocable) {
            "SKILL_USER_ONLY：此技能仅支持用户主动调用"
        }
        return content
    }

    /** Validate a deliverable before the model presents it. */
    fun present(relativePath: String): String {
        val file = resolveForWrite(relativePath)
        require(file.isFile) { "成果文件不存在：$relativePath" }
        return "成果已确认：$relativePath（${file.length()} 字节）"
    }

    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("文件校验已取消")
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicWrite(target: File, content: String) {
        LocalLargeFileIO.writeText(target, content)
    }

    /**
     * Resolve a workspace-relative path, or an absolute path when it names a shared-storage root.
     *
     * Absolute paths let the model address user media directly (`/storage/emulated/0/Download/x`).
     * This is the enforcement point for every file tool: the boundary is checked before any I/O, so
     * a rejected path never reaches the filesystem. It does not constrain [shell], which runs as a
     * child process and is bounded by the kernel instead.
     */
    private fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank()) { "路径不能为空" }
        val candidate = if (relativePath.startsWith(File.separator)) {
            File(relativePath).canonicalFile
        } else {
            File(canonicalRoot, relativePath).canonicalFile
        }
        require(boundary.isAllowed(candidate)) {
            "拒绝访问沙箱边界之外的路径：$relativePath"
        }
        return candidate
    }

    /** Resolve a path for writing. Writes are allowed wherever reads are. */
    private fun resolveForWrite(relativePath: String): File = resolve(relativePath)

    private fun safeWalk(
        directory: File,
        maxDepth: Int = MAX_SCAN_DEPTH,
        maxVisited: Int = MAX_SCAN_ENTRIES,
        maxMillis: Long = MAX_SCAN_MILLIS,
    ): Sequence<File> = sequence {
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(directory to 0)
        val deadlineNanos = if (maxMillis == Long.MAX_VALUE) Long.MAX_VALUE
        else System.nanoTime() + maxMillis.coerceAtLeast(1L) * 1_000_000L
        var visited = 0
        while (queue.isNotEmpty() && visited < maxVisited && System.nanoTime() <= deadlineNanos) {
            val (candidate, depth) = queue.removeFirst()
            if (!boundary.isAllowed(candidate) || Files.isSymbolicLink(candidate.toPath())) continue
            visited += 1
            yield(candidate)
            if (candidate.isDirectory && depth < maxDepth) {
                candidate.listFiles()
                    .orEmpty()
                    .sortedBy(File::getName)
                    .forEach { child ->
                        if (queue.size < maxVisited) queue.addLast(child to (depth + 1))
                    }
            }
        }
    }

    private fun displayPath(candidate: File): String =
        if (boundary.isWorkspace(candidate)) {
            candidate.canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
        } else {
            candidate.canonicalFile.invariantSeparatorsPath
        }

    private fun isInsideWorkspace(candidate: File): Boolean = boundary.isAllowed(candidate)

    private fun globRegex(glob: String): Regex {
        val output = StringBuilder("^")
        var index = 0
        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> {
                    if (index + 1 < glob.length && glob[index + 1] == '*') {
                        // "**/" means zero or more path segments, so it must also match a file
                        // directly under the searched directory. Treating "**" as plain ".*"
                        // would incorrectly require at least one slash.
                        if (index + 2 < glob.length && glob[index + 2] == '/') {
                            output.append("(?:.*/)?")
                            index += 2
                        } else {
                            output.append(".*")
                            index++
                        }
                    } else output.append("[^/]*")
                }
                '?' -> output.append("[^/]")
                '{' -> {
                    // Support the common extension alternation **/*.{md,json,yaml} without
                    // handing raw braces to Regex (where they mean a numeric repetition).
                    val closing = glob.indexOf('}', index + 1)
                    val alternatives = if (closing >= 0) {
                        glob.substring(index + 1, closing).split(',')
                    } else emptyList()
                    if (alternatives.size > 1 && alternatives.all { it.isNotEmpty() && '{' !in it && '}' !in it }) {
                        output.append("(?:")
                        alternatives.forEachIndexed { alternativeIndex, value ->
                            if (alternativeIndex > 0) output.append('|')
                            output.append(Regex.escape(value))
                        }
                        output.append(')')
                        index = closing
                    } else output.append("\\{")
                }
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '}', '[', ']', '\\' ->
                    output.append('\\').append(char)
                else -> output.append(char)
            }
            index++
        }
        return Regex(output.append('$').toString())
    }

    private fun readBounded(reader: Reader, onProgress: ((String) -> Unit)?): String {
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            if (output.length < MAX_SHELL_CHARS) {
                output.append(buffer, 0, minOf(read, MAX_SHELL_CHARS - output.length))
                onProgress?.invoke(output.toString())
            }
        }
        return output.toString()
    }

    private companion object {
        val BINARY_PREVIEW_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf",
            "zip", "7z", "rar", "gz", "tar", "apk", "jar", "so", "bin",
            "docx", "xlsx", "pptx", "mp3", "wav", "mp4", "mov", "webm",
            "db", "sqlite", "woff", "woff2", "ttf", "otf",
        )

        const val MAX_READ_LINES = 400
        const val MAX_READ_LINE_CHARS = 8 * 1024 * 1024
        const val MAX_SEARCH_LINE_CHARS = 8 * 1024 * 1024
        const val MAX_LIST_ROWS = 400
        const val MAX_SEARCH_ROWS = 200
        const val MAX_SEARCH_CHARS = 32_000
        const val MAX_SEARCH_PREVIEW_CHARS = 1_200
        const val MAX_SCAN_DEPTH = 32
        const val MAX_SCAN_ENTRIES = 5_000
        const val MAX_SCAN_MILLIS = 2_000L
        const val MAX_SHELL_CHARS = 65_536
        const val MAX_SHELL_TIMEOUT_SECONDS = 900
    }
}
