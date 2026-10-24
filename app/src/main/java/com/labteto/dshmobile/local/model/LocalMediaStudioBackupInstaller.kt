package com.labteto.dshmobile.local.model

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * Installation half of a future Media Studio archive restore.
 *
 * The caller MUST run this before constructing/opening the Studio SQLite owner,
 * on an empty app installation and with an exclusively owned private staging
 * directory. A user-facing restore entry point must not call this on a live DB.
 *
 * Commit ordering: validate all staged bytes -> preflight every target conflict ->
 * durable INSTALLING marker -> install verified media -> install SQLite LAST.
 * Interrupted installs retain staging, can be replayed byte-for-byte, and never
 * overwrite non-identical files or any pre-existing WAL/SHM.
 */
internal class LocalMediaStudioBackupInstaller(
    private val context: Context,
    private val json: Json,
) {
    fun install(staging: File): Int {
        val archive = LocalMediaStudioBackupSnapshot(context, json).validate(staging)
        val database = context.getDatabasePath("local_media_v1.db")
        val targetMedia = File(context.filesDir, "media-studio")
        check(!Files.isSymbolicLink(context.filesDir.toPath()) &&
            !Files.isSymbolicLink(targetMedia.toPath())) {
            "媒体恢复目标目录不安全"
        }
        // A normal Studio database may contain uncheckpointed WAL business receipts.
        // Never open/checkpoint/discard them as part of a destructive restore attempt.
        check(!File(database.path + "-wal").exists() &&
            !File(database.path + "-shm").exists()) {
            "目标媒体数据库仍有 WAL/SHM，拒绝覆盖已有创作事实"
        }
        if (database.exists()) check(
            Files.isRegularFile(database.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                sameBytes(database, archive.database)
        ) { "目标媒体数据库已有不同创作记录，禁止恢复覆盖" }

        val pairs = archive.mediaFiles.map { it to File(targetMedia, it.name) }
        // Before writing anything, reject every conflicting original. No partial
        // installation can follow from a known conflict in a later item.
        pairs.forEach { (original, destination) ->
            if (destination.exists()) check(
                Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    sameBytes(original, destination)
            ) { "目标已有不同内容的付费成果：${destination.name}" }
        }
        check(database.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "无法建立媒体 SQLite 恢复目录"
        }
        check(targetMedia.isDirectory || targetMedia.mkdirs()) {
            "无法建立媒体成果恢复目录"
        }
        val marker = File(staging, "INSTALLING")
        if (!marker.exists()) FileOutputStream(marker).use { stream ->
            stream.write("media-studio-v1".toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(marker.readText(Charsets.UTF_8) == "media-studio-v1") {
            "媒体恢复事务标记损坏，停止安装"
        }
        pairs.forEach { (original, destination) ->
            if (!destination.exists()) {
                val tmp = File(targetMedia, ".restore-${UUID.randomUUID()}")
                try {
                    original.inputStream().buffered().use { input ->
                        FileOutputStream(tmp).use { out ->
                            input.copyTo(out, 32 * 1024)
                            out.fd.sync()
                        }
                    }
                    check(sameBytes(original, tmp)) {
                        "媒体恢复过程中校验失败：${destination.name}"
                    }
                    // Under the startup owner lease, only our restore writes these
                    // app-private targets. Refuse a newly observed conflict.
                    check(!destination.exists()) {
                        "媒体恢复过程中目标文件被创建，停止安装：${destination.name}"
                    }
                    check(tmp.renameTo(destination)) {
                        "媒体原文件原子安装失败：${destination.name}"
                    }
                } finally { if (tmp.exists()) tmp.delete() }
            }
            check(sameBytes(original, destination)) {
                "媒体恢复目标文件校验失败：${destination.name}"
            }
        }
        if (!database.exists()) {
            val temp = File(database.parentFile, database.name + ".restore-${UUID.randomUUID()}")
            try {
                archive.database.inputStream().buffered().use { input ->
                    FileOutputStream(temp).use { out ->
                        input.copyTo(out, 32 * 1024)
                        out.fd.sync()
                    }
                }
                check(sameBytes(archive.database, temp)) {
                    "媒体数据库恢复临时文件校验失败"
                }
                check(!database.exists() && !File(database.path + "-wal").exists()) {
                    "媒体数据库恢复期间出现已有数据，停止覆盖"
                }
                check(temp.renameTo(database)) {
                    "媒体数据库原子安装失败"
                }
            } finally { if (temp.exists()) temp.delete() }
        }
        check(sameBytes(database, archive.database)) {
            "媒体数据库恢复后校验失败"
        }
        // The staged archive remains intact if a process dies before cleanup.
        // On next process start, a byte-identical target can finish cleanup.
        val restoredCount = SQLiteDatabase.openDatabase(
            database.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM media WHERE deleted=0", null).use { rows ->
                check(rows.moveToFirst()) { "媒体备份结果行数无法核实" }
                rows.getInt(0)
            }
        }
        check(staging.deleteRecursively()) { "媒体已恢复但待处理快照无法清除，请勿重复导入" }
        return restoredCount
    }

    private fun sameBytes(a: File, b: File): Boolean =
        a.isFile && b.isFile && a.length() == b.length() && hash(a) == hash(b)

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest()
    }
}
