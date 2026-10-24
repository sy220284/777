package com.labteto.dshmobile.local.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * An additive section of a future Session + Media Studio archive.
 *
 * Never interpret this section as a standalone all-app backup. Existing
 * session v2 ZIP import/export remains unchanged until an all-domain restore
 * coordinator can atomically stage both owners.
 *
 * Entry names and all decompressed bytes are constrained before they reach
 * the filesystem. The Media Store owns the frozen database and files.
 */
internal class LocalMediaStudioZipEntries(
    context: Context,
    json: Json,
) {
    private val validator = LocalMediaStudioBackupSnapshot(context, json)

    fun writeInto(zip: ZipOutputStream, prepared: LocalMediaStudioBackupSnapshot.Prepared): Long {
        val current = validator.validate(prepared.directory)
        val files = listOf(
            "$PREFIX$DB_ENTRY" to current.database,
            "$PREFIX$MANIFEST_ENTRY" to File(prepared.directory, MANIFEST_ENTRY),
        ) + current.mediaFiles.sortedBy(File::getName).map { "$PREFIX$MEDIA_ENTRY${it.name}" to it }
        var total = 0L
        for ((name, file) in files) {
            val before = hash(file)
            val length = file.length()
            zip.putNextEntry(ZipEntry(name))
            var copied = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    check(copied <= MAX_TOTAL_BYTES && total <= MAX_TOTAL_BYTES - n) {
                        "媒体 ZIP 内容超过上限"
                    }
                    zip.write(buffer, 0, n)
                    total += n
                }
            }
            zip.closeEntry()
            check(length == copied && before.contentEquals(hash(file))) {
                "媒体快照在打包中发生变化，拒绝导出"
            }
        }
        return total
    }

    /**
     * Caller dispatches only PREFIX entries from an existing ZipInputStream.
     * A future v3 restore must validate both Session and Studio stages before
     * writing its shared durable transaction marker.
     */
    inner class Receiver(private val stage: File) {
        private val received = linkedSetOf<String>()
        private var total = 0L

        init {
            check(!Files.isSymbolicLink(stage.toPath()) &&
                (stage.isDirectory || stage.mkdirs())) { "媒体 ZIP 暂存目录不安全" }
        }

        fun accept(entry: ZipEntry, zip: ZipInputStream) {
            require(!entry.isDirectory && entry.name.startsWith(PREFIX)) {
                "媒体 ZIP 成员类型错误"
            }
            val name = entry.name.removePrefix(PREFIX)
            val relative = when {
                name == DB_ENTRY || name == MANIFEST_ENTRY -> name
                name.startsWith(MEDIA_ENTRY) &&
                    validMediaName(name.removePrefix(MEDIA_ENTRY)) -> name
                else -> throw IllegalArgumentException("媒体 ZIP 存在未知或非法文件：$name")
            }
            require(received.size < MAX_ENTRIES && received.add(relative)) {
                "媒体 ZIP 存在重复或超量成员"
            }
            val limit = if (relative == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_TOTAL_BYTES
            val destination = File(stage, relative)
            val parent = requireNotNull(destination.parentFile)
            check(!Files.isSymbolicLink(parent.toPath()) &&
                (parent.isDirectory || parent.mkdirs()) &&
                !Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "媒体 ZIP 目标路径冲突或不安全：$relative"
            }
            try {
                FileOutputStream(destination).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var fileBytes = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        fileBytes += n
                        check(fileBytes <= limit && total <= MAX_TOTAL_BYTES - n) {
                            "媒体 ZIP 解压数据超限"
                        }
                        check(stage.usableSpace > n + SPACE_RESERVE) {
                            "媒体 ZIP 解压剩余空间不足"
                        }
                        out.write(buffer, 0, n)
                        total += n
                    }
                    out.fd.sync()
                }
            } catch (failure: Exception) {
                destination.delete()
                throw failure
            }
        }

        fun validateStaged(): LocalMediaStudioBackupSnapshot.Prepared {
            require(DB_ENTRY in received && MANIFEST_ENTRY in received) {
                "媒体 ZIP 缺少 SQLite 数据库或校验清单"
            }
            return validator.validate(stage)
        }
    }

    companion object {
        const val PREFIX = "media-studio/"
        const val DB_ENTRY = "media-studio.sqlite"
        const val MANIFEST_ENTRY = "media-studio.manifest"
        const val MEDIA_ENTRY = "media/"
        private const val SPACE_RESERVE = 64L * 1024L * 1024L
        private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L
        private const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
        private const val MAX_ENTRIES = 100_002
        private val NAME = Regex("[A-Za-z0-9._-]{1,180}")
        private fun validMediaName(value: String): Boolean =
            NAME.matches(value) && value != "." && value != ".." && !value.startsWith('.')

        private fun hash(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest()
        }
    }
}
