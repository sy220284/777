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

    /** Read a UTF-8 text range, bounded to keep one result out of the model's entire context. */
    fun read(relativePath: String, startLine: Int = 1, endLine: Int = startLine + 399): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        val from = startLine.coerceAtLeast(1)
        val requestedEnd = endLine.coerceAtLeast(from)
        // Stream only the requested page instead of loading the entire file into memory.
        val until = minOf(requestedEnd.toLong(), from.toLong() + MAX_READ_LINES - 1)
        val before = fingerprint(file)
        val rows = mutableListOf<String>()
        file.bufferedReader().use { reader ->
            var lineNumber = 0L
            while (lineNumber < until) {
                val line = readBoundedLine(reader, MAX_READ_LINE_CHARS) ?: break
                lineNumber++
                if (lineNumber >= from) rows += "$lineNumber: $line"
            }
        }
        val after = fingerprint(file)
        require(before == after) { "文件在读取过程中发生变化，请重新读取：$relativePath" }
        observations.put(file.path, after)
        return rows.joinToString("\n") +
            if (until < requestedEnd.toLong()) "\n[仅返回前 $MAX_READ_LINES 行；请从 start_line=${until + 1} 继续读取]" else ""
    }

    /** Replace one UTF-8 file, creating its parent directories. */
    fun write(relativePath: String, content: String): String {
        require(content.toByteArray().size <= MAX_WRITE_BYTES) { "单次写入超过 ${MAX_WRITE_BYTES / 1024} KB" }
        val file = resolveForWrite(relativePath)
        atomicWrite(file, content)
        observations.remove(file.path)
        return "已写入 $relativePath（${content.toByteArray().size} 字节）"
    }

    /** Store tool-owned artifacts (for example large web responses) without a second approval prompt. */
    fun writeToolArtifact(relativePath: String, content: String): String {
        val bytes = content.toByteArray()
        require(bytes.size <= MAX_TOOL_ARTIFACT_BYTES) {
            "工具产物超过 ${MAX_TOOL_ARTIFACT_BYTES / 1024 / 1024} MB：$relativePath"
        }
        val file = resolveForWrite(relativePath)
        atomicWrite(file, content)
        // Artifacts always live under the workspace root, so this relativization stays well defined.
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
        require(file.length() <= MAX_TOOL_ARTIFACT_BYTES) {
            "文件超过 ${MAX_TOOL_ARTIFACT_BYTES / 1024 / 1024} MB：$relativePath"
        }
        val before = fingerprint(file)
        val content = file.readText()
        val after = fingerprint(file)
        require(before == after) { "文件在读取过程中发生变化，请重新读取：$relativePath" }
        observations.put(file.path, after)
        return content
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
        require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024} KB：$relativePath" }
        val observed = requireFreshObservation(relativePath)
        val source = file.readText()
        require(observed == fingerprint(file)) {
            "文件在准备编辑时发生变化，请重新读取再编辑：$relativePath"
        }
        val first = source.indexOf(oldText)
        require(first >= 0) { "文件中没有找到待替换内容" }
        require(source.indexOf(oldText, first + oldText.length) < 0) { "待替换内容出现多次，请提供更长的唯一片段" }
        val result = source.replaceRange(first, first + oldText.length, newText)
        require(result.toByteArray().size <= MAX_WRITE_BYTES) { "编辑结果超过 ${MAX_WRITE_BYTES / 1024} KB" }
        atomicWrite(file, result)
        observations.put(file.path, fingerprint(file))
        return "已编辑 $relativePath"
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

    /** Snapshot real files for the app UI without exposing raw File handles outside the sandbox. */
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

    /** Bounded preview for local workspace files; binary files still return metadata and no text. */
    fun preview(relativePath: String, maxBytes: Int = 512 * 1024): LocalWorkspaceFilePreview {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        val safeMax = maxBytes.coerceIn(1, 2 * 1024 * 1024)
        val buffer = ByteArray(minOf(file.length().coerceAtMost(safeMax.toLong()).toInt(), safeMax))
        val count = file.inputStream().use { input ->
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }
        val bytes = if (count == buffer.size) buffer else buffer.copyOf(count)
        val binary = bytes.take(8_192).any { it == 0.toByte() } ||
            relativePath.substringAfterLast('.', "").lowercase() in BINARY_PREVIEW_EXTENSIONS
        val info = LocalWorkspaceFile(
            path = displayPath(file),
            bytes = file.length(),
            modifiedAt = file.lastModified(),
        )
        return LocalWorkspaceFilePreview(
            file = info,
            text = if (binary) null else String(bytes, Charsets.UTF_8),
            truncated = file.length() > bytes.size,
        )
    }

    /**
     * Execute Android's system shell with a hard timeout.
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
    ): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        val builder = ProcessBuilder(shellExecutable, "-c", command)
            .directory(canonicalRoot)
            .redirectErrorStream(true)
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
            builder = builder,
            timeoutMillis = timeoutSeconds.coerceIn(1, MAX_SHELL_TIMEOUT_SECONDS) * 1000L,
            maxChars = MAX_SHELL_CHARS,
            onProgress = onProgress,
        )
        when {
            result.timedOut && throwOnFailure -> {
                throw IllegalStateException(
                    "[shell][TOOL_TIMEOUT] 命令执行超时，已终止进程组。\n已产生输出：\n${result.stdout}",
                )
            }
            result.timedOut -> {
                "[shell][TOOL_TIMEOUT] 命令执行超时，已终止进程组。\n已产生输出：\n${result.stdout}"
            }
            result.exitCode != 0 && throwOnFailure -> {
                throw IllegalStateException(
                    "[shell][PROCESS_EXIT_${result.exitCode}] 后台命令异常结束。\n已产生输出：\n${result.stdout}",
                )
            }
            else -> {
                "退出码：${result.exitCode}\n${result.stdout}"
            }
        }
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
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicWrite(target: File, content: String) {
        val directory = target.parentFile ?: error("文件缺少父目录")
        require(directory.isDirectory || directory.mkdirs()) { "无法创建目录：$directory" }
        val temporary = Files.createTempFile(directory.toPath(), ".write-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (target.isFile && target.canExecute()) {
                require(temporary.toFile().setExecutable(true, true)) { "无法保留文件执行权限：$target" }
            }
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
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
                '.', '(', ')', '+', '|', '^', '$', '@', '%' -> output.append('\\').append(char)
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

        const val MAX_TEXT_BYTES = 5_242_880L
        const val MAX_READ_LINES = 400
        const val MAX_READ_LINE_CHARS = 8 * 1024 * 1024
        const val MAX_SEARCH_LINE_CHARS = 8 * 1024 * 1024
        const val MAX_WRITE_BYTES = 2_097_152
        const val MAX_TOOL_ARTIFACT_BYTES = 5 * 1024 * 1024
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
