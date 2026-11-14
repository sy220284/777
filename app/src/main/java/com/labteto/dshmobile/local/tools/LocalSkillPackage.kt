package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import com.labteto.dshmobile.local.files.parseLocalSkillMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * 单一技能包导入边界。文件和资源按流写入同一文件系统的临时目录，校验完成后
 * 才原子公布技能；大小只受设备实际可用存储空间约束。
 */
internal class LocalSkillPackageImporter(private val workspace: LocalWorkspace) {
    fun install(filename: String, bytes: ByteArray): String =
        ByteArrayInputStream(bytes).use { install(filename, it) }

    fun install(filename: String, input: InputStream, beforePublish: () -> Unit = {}): String {
        require(filename.endsWith(".zip", true) || filename.endsWith(".md", true)) {
            "请选择 SKILL.md 或 ZIP 技能包"
        }
        val systemRoot = File(workspace.path, ".dsh").apply { mkdirs() }
        val skillsRoot = File(systemRoot, "skills")
        require(!Files.isSymbolicLink(systemRoot.toPath()) &&
            !Files.isSymbolicLink(skillsRoot.toPath())) { "技能目录不能是符号链接" }
        val temporary = Files.createTempDirectory(systemRoot.toPath(), ".skill-import-").toFile()
        try {
            val unpacked = File(temporary, "unpacked").apply { mkdirs() }
            val selected = if (filename.endsWith(".zip", true)) {
                extractZip(input, unpacked)
            } else {
                input.use { source -> File(unpacked, "SKILL.md").outputStream().use { target -> source.copyTo(target) } }
                unpacked
            }
            val document = File(selected, "SKILL.md")
            require(document.isFile && !Files.isSymbolicLink(document.toPath())) {
                "技能包缺少 SKILL.md"
            }
            val id = readSkillId(document)
            require(LocalPresetSkillCatalog.entries.none { it.id == id }) {
                "预置技能标识已保留"
            }
            val destination = File(skillsRoot, id)
            require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) {
                "同名技能已经存在：$id"
            }
            require(skillsRoot.isDirectory || skillsRoot.mkdirs()) { "无法创建技能目录" }
            require(!destination.exists()) { "同名技能已经存在：$id" }
            beforePublish()
            try {
                Files.move(selected.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(selected.toPath(), destination.toPath())
            }
            return id
        } finally {
            if (temporary.exists()) require(temporary.deleteRecursively()) {
                "技能导入临时文件清理失败"
            }
        }
    }

    private fun extractZip(source: InputStream, unpacked: File): File {
        val files = linkedSetOf<String>()
        val unpackedPath = unpacked.canonicalFile.toPath()
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(name.isNotBlank() && !name.startsWith("/") && '\\' !in name &&
                    ':' !in name && name.split('/').none {
                        it == "." || it == ".." || (it.isEmpty() && !name.endsWith("/"))
                    }) { "技能包包含不安全的文件路径" }
                val target = File(unpacked, name)
                require(target.canonicalFile.toPath().startsWith(unpackedPath)) {
                    "技能包路径越过技能目录"
                }
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "无法创建技能目录" }
                } else {
                    require(files.add(name)) { "技能包存在重复文件：$name" }
                    require(target.parentFile.isDirectory || target.parentFile.mkdirs()) {
                        "无法创建技能文件目录"
                    }
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var sinceSpaceCheck = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sinceSpaceCheck += count
                            if (sinceSpaceCheck >= 8L * 1024 * 1024) {
                                require(unpacked.usableSpace > 32L * 1024 * 1024) {
                                    "设备剩余存储空间不足，已停止技能解压"
                                }
                                sinceSpaceCheck = 0
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "技能包为空" }
        if ("SKILL.md" in files) return unpacked
        val roots = files.filter { it.endsWith("/SKILL.md") && it.count { c -> c == '/' } == 1 }
        require(roots.size == 1) { "技能包需要在根目录或唯一的技能目录下包含 SKILL.md" }
        val prefix = roots.single().substringBefore('/') + "/"
        require(files.all { it.startsWith(prefix) }) { "技能包包含技能目录以外的文件" }
        return File(unpacked, prefix)
    }

    /** 只解析元数据，不将整份技能规则与全部资源装入内存。 */
    private fun readSkillId(document: File): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        java.io.InputStreamReader(document.inputStream(), decoder).buffered().use { reader ->
            require(reader.readLine()?.removePrefix("\uFEFF")?.trim() == "---") {
                "SKILL.md 缺少开头的元数据"
            }
            val header = mutableListOf("---")
            var closed = false
            repeat(95) {
                if (!closed) {
                    val line = reader.readLine() ?: throw IllegalArgumentException("SKILL.md 缺少完整元数据")
                    header += line
                    if (line.trim() == "---") closed = true
                }
            }
            require(closed && header.size >= 3) { "SKILL.md 缺少完整元数据" }
            var hasRules = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) hasRules = true
            }
            require(hasRules) { "SKILL.md 缺少执行规则" }
            val names = header.drop(1).dropLast(1).mapNotNull { line ->
                Regex("""^name:\s*["']?([a-z][a-z0-9-]{1,47})["']?\s*$""")
                    .matchEntire(line.trim())?.groupValues?.get(1)
            }
            require(names.size == 1) { "SKILL.md 必须包含唯一且有效的 name 字段（英文技能标识）" }
            val id = names.single()
            require(parseLocalSkillMetadata(id, header.joinToString("\n")).description.isNotBlank()) {
                "SKILL.md 缺少 description 技能说明"
            }
            return id
        }
    }
}
