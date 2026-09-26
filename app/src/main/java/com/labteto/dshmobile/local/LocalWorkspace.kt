package com.labteto.dshmobile.local

import java.io.File
import java.io.FileOutputStream
import java.io.Reader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

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
    private val observations = ConcurrentHashMap<String, String>()

    init {
        canonicalRoot.mkdirs()
    }

    /** Stable app-private workspace root. */
    val path: String get() = canonicalRoot.absolutePath

    /** Read a UTF-8 text range, bounded to keep one result out of the model's entire context. */
    fun read(relativePath: String, startLine: Int = 1, endLine: Int = startLine + 399): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024} KB：$relativePath" }
        val before = fingerprint(file)
        val lines = file.readLines()
        val after = fingerprint(file)
        require(before == after) { "文件在读取过程中发生变化，请重新读取：$relativePath" }
        observations[file.path] = after
        val from = (startLine.coerceAtLeast(1) - 1).coerceAtMost(lines.size)
        val to = endLine.coerceAtLeast(startLine).coerceAtMost(lines.size)
        return lines.subList(from, to).mapIndexed { index, line ->
            "${from + index + 1}: $line"
        }.joinToString("\n")
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
        observations[file.path] = after
        return content
    }

    /** Verify that the model observed the current file version before asking the user to approve an edit. */
    fun requireFreshObservation(relativePath: String): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        val observed = observations[file.path]
            ?: error("编辑前必须先读取文件：$relativePath")
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
        observations[file.path] = fingerprint(file)
        return "已编辑 $relativePath"
    }

    /** Glob-style file discovery without relying on a bundled desktop ripgrep binary. */
    fun glob(pattern: String, relativePath: String = "."): String {
        require(pattern.isNotBlank()) { "匹配模式不能为空" }
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "目录不存在：$relativePath" }
        val matcher = globRegex(pattern.replace('\\', '/'))
        val rows = safeWalk(directory)
            .filter { it.isFile && isInsideWorkspace(it) }
            .map { it.relativeTo(directory).invariantSeparatorsPath }
            .filter { matcher.matches(it) }
            .take(MAX_LIST_ROWS)
            .toList()
        return if (rows.isEmpty()) "未找到匹配文件" else rows.joinToString("\n")
    }

    /** List descendants without following a path outside the app workspace. */
    fun list(relativePath: String = ".", depth: Int = 3): String {
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "目录不存在：$relativePath" }
        val baseDepth = directory.toPath().nameCount
        val rows = safeWalk(directory)
            .filter {
                it != directory && isInsideWorkspace(it) &&
                    it.toPath().nameCount - baseDepth <= depth.coerceIn(1, 8)
            }
            .take(MAX_LIST_ROWS)
            .map { file ->
                val suffix = if (file.isDirectory) "/" else " (${file.length()} B)"
                file.relativeTo(canonicalRoot).invariantSeparatorsPath + suffix
            }
            .toList()
        return if (rows.isEmpty()) "目录为空" else rows.joinToString("\n")
    }

    /** Plain-text recursive search with deterministic, bounded output. */
    fun search(query: String, relativePath: String = "."): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val directory = resolve(relativePath)
        require(directory.exists()) { "路径不存在：$relativePath" }
        val files = if (directory.isFile) sequenceOf(directory) else safeWalk(directory)
        val matches = mutableListOf<String>()
        files.filter { it.isFile && isInsideWorkspace(it) && it.length() <= MAX_TEXT_BYTES }.forEach { file ->
            if (matches.size >= MAX_SEARCH_ROWS) return@forEach
            runCatching { file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (matches.size < MAX_SEARCH_ROWS && line.contains(query, ignoreCase = true)) {
                        matches += "${file.relativeTo(canonicalRoot).invariantSeparatorsPath}:${index + 1}: $line"
                    }
                }
            } }
        }
        return if (matches.isEmpty()) "未找到匹配内容" else matches.joinToString("\n")
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
            path = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
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
    ): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        val builder = ProcessBuilder(shellExecutable, "-c", command)
            .directory(canonicalRoot)
            .redirectErrorStream(true)
        val shellTemp = File(canonicalRoot, ".dsh/tmp").apply { mkdirs() }
        builder.environment().apply {
            clear()
            environmentProvider()
                .filterKeys { key -> SAFE_ENV_NAME.matches(key) && key !in BLOCKED_SHELL_ENV_KEYS }
                .filterValues { value -> '\u0000' !in value }
                .forEach { (key, value) -> put(key, value) }
            val runtimePath = extraSearchPaths()
                .filter(File::isDirectory)
                .joinToString(File.pathSeparator) { it.absolutePath }
            put(
                "PATH",
                (listOf(runtimePath) + SAFE_SYSTEM_PATHS)
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString(File.pathSeparator),
            )
            put("HOME", canonicalRoot.absolutePath)
            put("TMPDIR", shellTemp.absolutePath)
            put("PWD", canonicalRoot.absolutePath)
        }
        val result = com.labteto.dshmobile.runtime.executeManagedProcess(
            builder = builder,
            timeoutMillis = timeoutSeconds.coerceIn(1, MAX_SHELL_TIMEOUT_SECONDS) * 1000L,
            maxChars = MAX_SHELL_CHARS,
            onProgress = onProgress,
        )
        if (result.timedOut) {
            "[shell][TOOL_TIMEOUT] 命令执行超时，已终止进程组。\n已产生输出：\n${result.stdout}"
        } else {
            "退出码：${result.exitCode}\n${result.stdout}"
        }
    }

    /** Installed workspace skill names. */
    fun skills(): List<String> {
        val directory = resolve(".dsh/skills")
        return directory.listFiles().orEmpty()
            .filter { File(it, "SKILL.md").isFile }
            .map { it.name }
            .sorted()
    }

    /** Read one installed skill instruction file. */
    fun readSkill(name: String): String = read(".dsh/skills/$name/SKILL.md", 1, 800)

    /** Validate a deliverable before the model presents it. */
    fun present(relativePath: String): String {
        val file = resolveForWrite(relativePath)
        require(file.isFile) { "成果文件不存在：$relativePath" }
        return "成果已确认：$relativePath（${file.length()} 字节）"
    }

    /** Whether a write to [relativePath] may skip the approval prompt under the current boundary. */
    fun canAutoApproveWrite(relativePath: String): Boolean =
        runCatching { boundary.canAutoApprove(resolve(relativePath)) }.getOrDefault(false)

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

    private fun safeWalk(directory: File): Sequence<File> =
        directory.walkTopDown()
            .onEnter { candidate ->
                boundary.isAllowed(candidate) && !Files.isSymbolicLink(candidate.toPath())
            }
            .asSequence()

    private fun isInsideWorkspace(candidate: File): Boolean = boundary.isAllowed(candidate)

    private fun globRegex(glob: String): Regex {
        val output = StringBuilder("^")
        var index = 0
        while (index < glob.length) {
            when (val char = glob[index]) {
                '*' -> {
                    if (index + 1 < glob.length && glob[index + 1] == '*') {
                        output.append(".*")
                        index++
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
        const val MAX_WRITE_BYTES = 2_097_152
        const val MAX_TOOL_ARTIFACT_BYTES = 5 * 1024 * 1024
        const val MAX_LIST_ROWS = 400
        const val MAX_SEARCH_ROWS = 200
        const val MAX_SHELL_CHARS = 65_536
        val SAFE_ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val BLOCKED_SHELL_ENV_KEYS = setOf(
            "PATH", "HOME", "PWD", "OLDPWD", "TMPDIR",
            "LD_PRELOAD", "DYLD_INSERT_LIBRARIES",
        )
        val SAFE_SYSTEM_PATHS = listOf("/system/bin", "/system/xbin", "/product/bin", "/vendor/bin")
        const val MAX_SHELL_TIMEOUT_SECONDS = 900
    }
}
