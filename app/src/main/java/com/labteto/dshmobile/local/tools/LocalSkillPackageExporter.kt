package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.local.files.LocalWorkspace
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports an installed skill as an import-compatible ZIP without executing its contents.
 * Follows LocalSkillPackageImporter size/path budgets and rejects filesystem links.
 */
internal class LocalSkillPackageExporter(private val workspace: LocalWorkspace) {
    fun export(id: String): ByteArray = ByteArrayOutputStream().also { exportToStream(id, it) }.toByteArray()

    fun exportToFile(id: String, destination: File) {
        destination.outputStream().buffered().use { exportToStream(id, it) }
    }

    private fun exportToStream(id: String, destination: OutputStream) {
        require(id.matches(Regex("[a-z][a-z0-9-]{1,47}"))) { "技能标识无效" }
        val parent = File(workspace.path, ".dsh")
        val catalog = File(parent, "skills")
        val root = File(catalog, id)
        require(!Files.isSymbolicLink(parent.toPath()) &&
            !Files.isSymbolicLink(catalog.toPath()) &&
            !Files.isSymbolicLink(root.toPath()) &&
            root.isDirectory && root.canonicalFile.parentFile == catalog.canonicalFile) {
            "技能目录不存在或路径不安全"
        }
        val paths = mutableListOf<Path>()
        Files.walk(root.toPath()).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                paths.add(iterator.next())
            }
        }
        val files = paths.filter { path ->
            require(!Files.isSymbolicLink(path)) { "技能目录含符号链接，拒绝打包" }
            val relative = root.toPath().relativize(path).toString().replace(File.separatorChar, '/')
            require(relative.length <= 512 && relative.count { it == '/' } <= 16 &&
                (relative.isEmpty() || relative.split('/').all {
                    it.isNotBlank() && it != "." && it != ".."
                })) { "技能包路径格式无效" }
            when {
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> false
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> true
                else -> throw IllegalArgumentException("技能目录包含未知文件类型")
            }
        }.sortedBy { root.toPath().relativize(it).toString() }
        require(files.isNotEmpty() && files.any {
            root.toPath().relativize(it).toString() == "SKILL.md"
        }) { "技能规则 SKILL.md 不存在" }
        ZipOutputStream(destination).use { zip ->
            val buffer = ByteArray(8192)
            for (path in files) {
                val relative = root.toPath().relativize(path).toString()
                    .replace(File.separatorChar, '/')
                val before = Files.readAttributes(
                    path, java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                zip.putNextEntry(ZipEntry(relative).apply { time = 0L })
                var written = 0L
                Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use {
                    channel ->
                    val source = Channels.newInputStream(channel)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= before.size()) { "技能文件在打包时发生变化" }
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                val after = Files.readAttributes(
                    path, java.nio.file.attribute.BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                require(written == before.size() && before.size() == after.size() &&
                    before.lastModifiedTime() == after.lastModifiedTime() &&
                    before.fileKey() == after.fileKey()) {
                    "技能文件在打包时发生变化，请重新分享"
                }
            }
        }
    }
}
