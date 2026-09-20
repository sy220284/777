package com.labteto.dshmobile.local

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Sandboxed filesystem and shell provider for the on-device Harness. */
class LocalWorkspace(private val root: File) {
    init {
        root.mkdirs()
    }

    /** Stable app-private workspace root. */
    val path: String get() = root.absolutePath

    /** Read a UTF-8 text range, bounded to keep one result out of the model's entire context. */
    fun read(relativePath: String, startLine: Int = 1, endLine: Int = startLine + 399): String {
        val file = resolve(relativePath)
        require(file.isFile) { "文件不存在：$relativePath" }
        require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024} KB：$relativePath" }
        val lines = file.readLines()
        val from = (startLine.coerceAtLeast(1) - 1).coerceAtMost(lines.size)
        val to = endLine.coerceAtLeast(startLine).coerceAtMost(lines.size)
        return lines.subList(from, to).mapIndexed { index, line ->
            "${from + index + 1}: $line"
        }.joinToString("\n")
    }

    /** Replace one UTF-8 file, creating its parent directories. */
    fun write(relativePath: String, content: String): String {
        require(content.toByteArray().size <= MAX_WRITE_BYTES) { "单次写入超过 ${MAX_WRITE_BYTES / 1024} KB" }
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "已写入 $relativePath（${content.toByteArray().size} 字节）"
    }

    /** List descendants without following a path outside the app workspace. */
    fun list(relativePath: String = ".", depth: Int = 3): String {
        val directory = resolve(relativePath)
        require(directory.isDirectory) { "目录不存在：$relativePath" }
        val baseDepth = directory.toPath().nameCount
        val rows = directory.walkTopDown()
            .onEnter { it.canonicalFile.path.startsWith(root.canonicalFile.path) }
            .filter { it != directory && it.toPath().nameCount - baseDepth <= depth.coerceIn(1, 8) }
            .take(MAX_LIST_ROWS)
            .map { file ->
                val suffix = if (file.isDirectory) "/" else " (${file.length()} B)"
                file.relativeTo(root).invariantSeparatorsPath + suffix
            }
            .toList()
        return if (rows.isEmpty()) "目录为空" else rows.joinToString("\n")
    }

    /** Plain-text recursive search with deterministic, bounded output. */
    fun search(query: String, relativePath: String = "."): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val directory = resolve(relativePath)
        require(directory.exists()) { "路径不存在：$relativePath" }
        val files = if (directory.isFile) sequenceOf(directory) else directory.walkTopDown().asSequence()
        val matches = mutableListOf<String>()
        files.filter { it.isFile && it.length() <= MAX_TEXT_BYTES }.forEach { file ->
            if (matches.size >= MAX_SEARCH_ROWS) return@forEach
            runCatching { file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (matches.size < MAX_SEARCH_ROWS && line.contains(query, ignoreCase = true)) {
                        matches += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: $line"
                    }
                }
            } }
        }
        return if (matches.isEmpty()) "未找到匹配内容" else matches.joinToString("\n")
    }

    /** Execute Android's system shell in the workspace with a hard timeout. */
    suspend fun shell(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "命令不能为空" }
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        try {
            withTimeout(timeoutSeconds.coerceIn(1, 120) * 1_000L) {
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val code = process.waitFor()
                "退出码：$code\n${output.take(MAX_SHELL_CHARS)}"
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
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

    private fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank()) { "路径不能为空" }
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        require(candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            "拒绝访问工作区之外的路径"
        }
        return candidate
    }

    private companion object {
        const val MAX_TEXT_BYTES = 1_048_576L
        const val MAX_WRITE_BYTES = 2_097_152
        const val MAX_LIST_ROWS = 400
        const val MAX_SEARCH_ROWS = 200
        const val MAX_SHELL_CHARS = 65_536
    }
}
