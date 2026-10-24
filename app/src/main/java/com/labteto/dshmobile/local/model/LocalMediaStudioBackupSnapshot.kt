package com.labteto.dshmobile.local.model

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * Preparation phase for a future all-domain backup. This is not yet connected to
 * Session ZIP export or restore: a one-way export must never be marketed as a full backup.
 *
 * Called under LocalMediaStore's lock so no Store mutation can race the WAL-aware
 * SQLite snapshot and the copies of the media that snapshot references.
 */
internal class LocalMediaStudioBackupSnapshot(
    private val context: Context,
    private val json: Json,
) {
    internal data class Prepared(
        val directory: File,
        val database: File,
        val mediaFiles: List<File>,
        val totalBytes: Long,
    )

    /** Stage everything atomically into a new private directory; do not touch live originals. */
    fun prepare(source: LocalMediaDatabase, target: File): Prepared {
        require(!target.exists()) { "媒体备份临时目录已存在，拒绝覆盖" }
        check(target.mkdirs()) { "无法建立媒体备份隔离目录" }
        try {
            val snapshot = File(target, DATABASE_NAME)
            source.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))
            val names = referencedFiles(snapshot)
            check(names.size <= MAX_FILES) { "媒体备份文件数量超过上限" }
            val output = File(target, MEDIA_DIR)
            check(output.mkdirs()) { "媒体备份文件目录无法创建" }
            val media = File(context.filesDir, "media-studio")
            check(!Files.isSymbolicLink(media.toPath())) { "媒体成果目录是符号链接" }
            var total = snapshot.length()
            val manifest = StringBuilder("media-studio-v1\n")
            manifest.append("database\t").append(snapshot.length())
                .append('\t').append(hash(snapshot)).append('\n')
            val staged = mutableListOf<File>()
            for (name in names.sorted()) {
                require(validName(name)) { "媒体成果引用的文件名不安全：$name" }
                val original = File(media, name)
                check(Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "媒体成果的本地原文件缺失或不安全：$name；停止备份，禁止生成不完整归档"
                }
                val size = original.length()
                check(size >= 0 && total <= MAX_TOTAL_BYTES - size) {
                    "媒体备份数据超过上限"
                }
                check(target.usableSpace > size + FREE_SPACE_RESERVE) {
                    "本机空间不足，停止媒体成果备份"
                }
                val stagedFile = File(output, name)
                original.inputStream().buffered().use { input ->
                    stagedFile.outputStream().buffered().use { destination ->
                        input.copyTo(destination, 32 * 1024)
                    }
                }
                val checksum = hash(stagedFile)
                check(stagedFile.length() == size &&
                    Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    original.length() == size && hash(original) == checksum) {
                    "媒体原文件在备份时变化：$name；禁止提交不一致的成果备份"
                }
                manifest.append(name).append('\t').append(size)
                    .append('\t').append(checksum).append('\n')
                staged += stagedFile
                total += size
            }
            File(target, MANIFEST_NAME).writeText(manifest.toString(), Charsets.UTF_8)
            val prepared = Prepared(target, snapshot, staged, total)
            validate(prepared.directory)
            return prepared
        } catch (failure: Exception) {
            target.deleteRecursively()
            throw failure
        }
    }

    /** A later ZIP installer must run this before authorizing any restore marker. */
    fun validate(target: File): Prepared {
        val database = File(target, DATABASE_NAME)
        val manifest = File(target, MANIFEST_NAME)
        require(manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) {
            "媒体成果清单缺失或超过上限"
        }
        val lines = manifest.readLines(Charsets.UTF_8)
        require(lines.firstOrNull() == "media-studio-v1" && lines.size >= 2) {
            "媒体成果备份版本无效"
        }
        val databaseRow = lines[1].split('\t')
        require(databaseRow.size == 3 && databaseRow[0] == "database" &&
            databaseRow[1].toLongOrNull() == database.length() &&
            validHash(databaseRow[2]) && database.isFile &&
            hash(database) == databaseRow[2]) { "媒体成果 SQLite 文件校验失败" }
        val expected = referencedFiles(database)
        val names = linkedSetOf<String>()
        val files = mutableListOf<File>()
        var total = database.length()
        require(lines.size - 2 <= MAX_FILES) { "媒体成果数量超限" }
        for (line in lines.drop(2)) {
            val parts = line.split('\t')
            require(parts.size == 3 && validName(parts[0]) &&
                validHash(parts[2]) && names.add(parts[0])) { "媒体清单文件名或校验码非法" }
            val size = parts[1].toLongOrNull()
            require(size != null && size >= 0 && total <= MAX_TOTAL_BYTES - size) {
                "媒体清单大小非法"
            }
            val file = File(target, "$MEDIA_DIR/${parts[0]}")
            require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                file.length() == size && hash(file) == parts[2]) {
                "媒体原文件与备份校验不一致：${parts[0]}"
            }
            total += size
            files += file
        }
        require(names == expected) { "媒体 SQLite 引用与备份原文件集合不一致" }
        val stagedEntries = File(target, MEDIA_DIR).listFiles()
            ?: error("媒体备份文件目录无法读取")
        require(stagedEntries.size == names.size && stagedEntries.all { it.name in names }) {
            "媒体备份包含未登记或缺失的原文件"
        }
        return Prepared(target, database, files, total)
    }

    private fun referencedFiles(snapshot: File): Set<String> {
        require(snapshot.isFile && snapshot.length() >= 100L) { "媒体成果 SQLite 快照缺失" }
        return SQLiteDatabase.openDatabase(
            snapshot.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            LocalMediaDatabase.validateBackupStructure(db)
            db.rawQuery("PRAGMA quick_check", null).use { rows ->
                check(rows.moveToFirst() && rows.getString(0) == "ok") {
                    "媒体成果 SQLite 完整性检查失败"
                }
            }
            db.rawQuery("SELECT value FROM metadata WHERE id='migrated'", null).use { rows ->
                require(rows.moveToFirst() && rows.getString(0) == "1") {
                    "媒体成果 SQLite 迁移标记缺失"
                }
            }
            db.rawQuery("SELECT payload FROM media WHERE deleted=0", null).use { rows ->
                buildSet {
                    while (rows.moveToNext()) {
                        val entry = json.decodeFromString(LocalMediaRecord.serializer(), rows.getString(0))
                        entry.result?.fileName?.let(::add)
                        entry.video?.fileName?.let(::add)
                    }
                }
            }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun validName(name: String) =
        NAME.matches(name) && name != "." && name != ".." && !name.startsWith('.')

    private fun validHash(value: String) = HASH.matches(value)

    private companion object {
        const val DATABASE_NAME = "media-studio.sqlite"
        const val MANIFEST_NAME = "media-studio.manifest"
        const val MEDIA_DIR = "media"
        const val MAX_FILES = 100_000
        const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
        const val FREE_SPACE_RESERVE = 64L * 1024L * 1024L
        val NAME = Regex("[A-Za-z0-9._-]{1,180}")
        val HASH = Regex("[a-f0-9]{64}")
    }
}
