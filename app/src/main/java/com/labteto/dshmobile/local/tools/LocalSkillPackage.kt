package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** One import boundary for a SKILL.md file or a zipped skill directory; never execute contents. */
internal class LocalSkillPackageImporter(private val workspace: LocalWorkspace) {
    companion object {
        const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
        private const val MAX_DOCUMENT_BYTES = 24_000
        private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 24 * 1024 * 1024
        private const val MAX_ENTRY_COUNT = 128
    }

    fun install(filename: String, bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ARCHIVE_BYTES) { "技能文件不能为空或超过 8 MB" }
        val entries = when {
            filename.endsWith(".zip", ignoreCase = true) -> decodeZip(bytes)
            filename.endsWith(".md", ignoreCase = true) -> linkedMapOf("SKILL.md" to bytes)
            else -> throw IllegalArgumentException("请选择 SKILL.md 或 ZIP 技能包")
        }
        val documentBytes = entries["SKILL.md"] ?: throw IllegalArgumentException("技能包缺少 SKILL.md")
        require(documentBytes.size <= MAX_DOCUMENT_BYTES) { "SKILL.md 超过 24 KB，请拆分附属资料后重试" }
        val document = decodeUtf8(documentBytes)
        entries["SKILL.md"] = document.toByteArray(Charsets.UTF_8)
        val lines = document.lines()
        require(lines.firstOrNull()?.trim() == "---") { "SKILL.md 缺少开头的元数据" }
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        require(end in 2..95 && lines.drop(end + 1).any { it.isNotBlank() }) { "SKILL.md 缺少完整元数据或执行规则" }
        val names = lines.subList(1, end).mapNotNull { line ->
            Regex("""^name:\s*["']?([a-z][a-z0-9-]{1,47})["']?\s*$""")
                .matchEntire(line.trim())?.groupValues?.get(1)
        }
        require(names.size == 1) { "SKILL.md 必须包含唯一且有效的 name 字段（英文技能标识）" }
        val id = names.single()
        require(parseLocalSkillMetadata(id, document).description.isNotBlank()) { "SKILL.md 缺少 description 技能说明" }
        require(LocalPresetSkillCatalog.entries.none { it.id == id }) { "预置技能标识已保留" }

        val skillRoot = File(workspace.path, ".dsh/skills")
        val destination = File(skillRoot, id)
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "同名技能已经存在：$id" }
        val stagingRoot = File(workspace.path, ".dsh").apply { mkdirs() }
        require(!Files.isSymbolicLink(stagingRoot.toPath()) && !Files.isSymbolicLink(skillRoot.toPath())) {
            "技能目录不能是符号链接"
        }
        val staging = Files.createTempDirectory(stagingRoot.toPath(), ".skill-import-").toFile()
        try {
            // Stage outside the catalog, so a failed import never advertises a partial skill.
            for ((name, payload) in entries) {
                val target = File(staging, name)
                require(target.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath())) { "技能包路径不安全" }
                require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法创建技能目录" }
                Files.write(target.toPath(), payload)
            }
            require(skillRoot.isDirectory || skillRoot.mkdirs()) { "无法创建技能目录" }
            require(!destination.exists()) { "同名技能已经存在：$id" }
            try {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), destination.toPath())
            }
            return id
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun decodeZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val raw = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRY_COUNT) { "技能包目录和文件数量超过安全限制" }
                val name = entry.name
                require(name.length <= 512 && name.count { it == '/' } <= 16) { "技能包文件路径过长或嵌套过深" }
                require(name.isNotBlank() && '\\' !in name && !name.startsWith("/") &&
                    ':' !in name && name.split('/').none { it == "." || it == ".." || it.isEmpty() && !name.endsWith("/") }) {
                    "技能包包含不安全的文件路径"
                }
                if (!entry.isDirectory) {
                    require(raw.size < MAX_ENTRY_COUNT && name !in raw) { "技能包文件数量过多或存在重名文件" }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        expanded += count
                        require(out.size() + count <= MAX_ENTRY_BYTES && expanded <= MAX_TOTAL_BYTES) {
                            "技能包解压后的文件超出安全限制"
                        }
                        out.write(buffer, 0, count)
                    }
                    raw[name] = out.toByteArray()
                }
                zip.closeEntry()
            }
        }
        require(raw.isNotEmpty()) { "技能包为空" }
        val roots = raw.keys.filter { it.endsWith("/SKILL.md") && it.count { c -> c == '/' } == 1 }
        val prefix = when {
            "SKILL.md" in raw -> ""
            roots.size == 1 -> roots.single().substringBefore("/") + "/"
            else -> throw IllegalArgumentException("技能包需要在根目录或唯一的技能目录下包含 SKILL.md")
        }
        require(raw.keys.all { it.startsWith(prefix) }) { "技能包包含技能目录以外的文件" }
        return LinkedHashMap<String, ByteArray>().apply {
            for ((name, content) in raw) {
                val relative = name.removePrefix(prefix)
                require(relative.isNotBlank())
                put(relative, content)
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
}
