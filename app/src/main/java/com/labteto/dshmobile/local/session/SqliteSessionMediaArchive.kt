package com.labteto.dshmobile.local.session

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lossless local media companion to the SQLite session backup.
 *
 * The catalog is still being backfilled, so enumerate all safely published files,
 * including media mentioned by historical transcript blocks but absent from the
 * incremental SQLite asset catalog. Every member is covered by a SHA-256 manifest.
 *
 * This is NOT a workspace export: only the app-owned .dsh/attachments directory.
 */
internal class SqliteSessionMediaArchive(
    context: Context,
    private val workspace: File = File(context.filesDir, "local-harness/workspace"),
) {
    private val directory get() = File(workspace, ".dsh/attachments")

    /** Write all media bytes and a complete manifest, rejecting unsafe/unreadable files. */
    fun export(zip: ZipOutputStream, eventSnapshot: File): Long {
        val dir = directory
        if (dir.exists()) {
            check(!Files.isSymbolicLink(dir.toPath()) &&
                !Files.isSymbolicLink(dir.parentFile.toPath()) &&
                dir.isDirectory) { "本地附件目录不是安全目录，已取消备份" }
        }
        val files = if (dir.exists()) requireNotNull(dir.listFiles()) {
            "附件目录无法完整枚举，已取消备份"
        }.toList() else emptyList()
        // Published content-addressed files are immutable. Hidden .incoming-,
        // .normalized- and .generated- are uncommitted temporary writes.
        val committed = files.filterNot {
            it.name.startsWith(".incoming-") ||
                it.name.startsWith(".normalized-") ||
                it.name.startsWith(".generated-") ||
                it.name.startsWith(".restore-")
        }
        check(committed.size <= MAX_MEDIA_FILES) { "附件数量超过备份上限" }
        val indexed = linkedMapOf<String, Pair<Long, String>>()
        val manifest = StringBuilder()
        var written = 0L
        for (file in committed.sortedBy(File::getName)) {
            check(validFilename(file.name) && !file.name.startsWith(".") &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "附件目录包含不安全或未提交的文件，已取消备份：${file.name}"
            }
            check(written <= MAX_MEDIA_BYTES - file.length()) {
                "附件总量超过备份上限"
            }
            zip.putNextEntry(ZipEntry("$MEDIA_PREFIX${file.name}"))
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= MAX_MEDIA_BYTES) { "附件读取超过上限" }
                    md.update(buffer, 0, count)
                    zip.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
            check(size == file.length() &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "附件备份期间文件发生变化"
            }
            // A digest-named file MUST match its identity, not just the manifest.
            val hex = md.digest().toHex()
            val namedDigest = file.name.substringBefore('.')
            if (SHA256.matches(namedDigest)) check(hex == namedDigest) {
                "附件内容与哈希文件名不一致：${file.name}"
            }
            manifest.append(file.name).append('\t').append(size)
                .append('\t').append(hex).append('\n')
            indexed[file.name] = size to hex
            written += size
        }
        verifyCatalogLocations(eventSnapshot, indexed)
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        val payload = manifest.toString().toByteArray(Charsets.UTF_8)
        zip.write(payload)
        zip.closeEntry()
        return written + payload.size
    }

    /**
     * Verify that the extracted archive is complete and every byte matches its
     * manifest before a READY marker is allowed to exist.
     */
    fun validateStaged(staging: File, archiveNames: Set<String>) {
        require(MANIFEST_ENTRY in archiveNames) { "SQLite 备份缺少媒体清单" }
        val manifest = File(staging, MANIFEST_ENTRY)
        require(manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) {
            "媒体清单无效或超过上限"
        }
        val lines = manifest.readLines(Charsets.UTF_8).filter(String::isNotEmpty)
        check(lines.size <= MAX_MEDIA_FILES) { "媒体清单条目过多" }
        val expected = linkedSetOf<String>()
        val indexed = linkedMapOf<String, Pair<Long, String>>()
        var total = 0L
        for (line in lines) {
            val parts = line.split('\t')
            require(parts.size == 3 && validFilename(parts[0]) &&
                !parts[0].startsWith(".")) { "媒体清单存在非法文件名" }
            val filename = parts[0]
            require(expected.add(filename) && SHA256.matches(parts[2])) {
                "媒体清单存在重复文件或校验码无效"
            }
            val size = parts[1].toLongOrNull()
            require(size != null && size >= 0L && size <= MAX_MEDIA_BYTES - total) {
                "媒体大小非法"
            }
            total += size
            val file = File(staging, "$STAGED_MEDIA_DIR/$filename")
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == size && hash(file) == parts[2]) {
                "媒体内容不匹配：$filename"
            }
            val namedDigest = filename.substringBefore('.')
            if (SHA256.matches(namedDigest)) check(namedDigest == parts[2]) {
                "媒体文件名与内容哈希不符"
            }
            indexed[filename] = size to parts[2]
        }
        require(archiveNames == expected.mapTo(mutableSetOf()) { "$MEDIA_PREFIX$it" } +
            setOf(MANIFEST_ENTRY, "snapshot.sqlite", "events.sqlite")) {
            "SQLite 备份缺少媒体文件或包含多余成员"
        }
        verifyCatalogLocations(File(staging, "events.sqlite"), indexed)
    }

    private fun verifyCatalogLocations(
        eventSnapshot: File,
        files: Map<String, Pair<Long, String>>,
    ) {
        SQLiteDatabase.openDatabase(
            eventSnapshot.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            val table = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name='local_asset_locations'", null,
            ).use { it.moveToFirst() }
            val source = if (table) "local_asset_locations" else "local_assets"
            val present = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(source),
            ).use { it.moveToFirst() }
            if (!present) return@use
            db.rawQuery(
                "SELECT relative_path,asset_id,byte_size FROM $source", null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0)
                    val assetId = cursor.getString(1)
                    val size = cursor.getLong(2)
                    require(path.startsWith(".dsh/attachments/") &&
                        SHA256.matches(assetId)) { "资源索引包含非法本地附件路径" }
                    val filename = path.removePrefix(".dsh/attachments/")
                    require(validFilename(filename) &&
                        files[filename] == (size to assetId)) {
                        "数据库登记的附件缺失、大小不符或校验码不符：$filename"
                    }
                }
            }
        }
    }

    /**
     * Install bytes before restoring the two databases; a crash after media copy
     * leaves only idempotent extra files, never a live DB pointing to missing bytes.
     */
    fun installStaged(staging: File) {
        val from = File(staging, STAGED_MEDIA_DIR)
        if (!from.exists()) return
        val target = directory
        check(!Files.isSymbolicLink(workspace.toPath()) &&
            !Files.isSymbolicLink(File(workspace, ".dsh").toPath()) &&
            !Files.isSymbolicLink(target.toPath())) { "媒体恢复路径存在符号链接" }
        check(target.mkdirs() || target.isDirectory) { "无法创建媒体恢复目录" }
        val files = from.listFiles().orEmpty().sortedBy(File::getName)
        // Check every conflict first; never partially install over user-owned data.
        files.forEach { file ->
            val dest = File(target, file.name)
            if (dest.exists()) check(Files.isRegularFile(dest.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                sameBytes(file, dest)) { "本地已有不同内容的同名附件：${file.name}" }
        }
        files.forEach { file ->
            val dest = File(target, file.name)
            if (!dest.exists()) {
                val tmp = File(target, ".restore-${java.util.UUID.randomUUID()}")
                try {
                    file.copyTo(tmp, overwrite = false)
                    check(sameBytes(file, tmp)) { "媒体恢复临时文件校验失败" }
                    try {
                        Files.move(tmp.toPath(), dest.toPath())
                    } catch (ex: java.nio.file.FileAlreadyExistsException) {
                        check(sameBytes(file, dest)) { "恢复期间存在相同路径的不同附件" }
                    }
                } finally { tmp.delete() }
            }
        }
    }

    companion object {
        const val MANIFEST_ENTRY = "attachments.manifest"
        const val MEDIA_PREFIX = "attachments/"
        const val STAGED_MEDIA_DIR = "media"
        const val MAX_MEDIA_BYTES = 64L * 1024L * 1024L * 1024L
        const val MAX_MEDIA_FILES = 100_000
        const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
        private val NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,180}")
        private val SHA256 = Regex("[a-f0-9]{64}")

        fun validFilename(name: String): Boolean =
            NAME.matches(name) && name != "." && name != ".."

        fun hash(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val chunk = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    md.update(chunk, 0, n)
                }
            }
            return md.digest().toHex()
        }

        fun sameBytes(a: File, b: File): Boolean =
            a.length() == b.length() && hash(a) == hash(b)

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
