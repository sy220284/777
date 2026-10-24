package com.labteto.dshmobile.local.model

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Bounded transport for a *staged* Studio snapshot. This archive is not exposed
 * as a user-facing complete backup until the Session+Studio restore coordinator
 * can install both authorities without overwriting either one.
 *
 * The ZIP namespace is reserved for embedding inside a future joint Session ZIP.
 * Never stream live media originals here; only immutable prepared snapshot bytes.
 */
internal class LocalMediaStudioBackupZip(
    private val context: Context,
    private val json: Json,
) {
    /** Stream an already prepared and validated snapshot into the caller's ZIP. */
    fun writeEntries(staging: File, zip: ZipOutputStream): Long {
        val verifier = LocalMediaStudioBackupSnapshot(context, json)
        val prepared = verifier.validate(staging)
        val files = listOf(
            File(staging, "media-studio.manifest") to MANIFEST_ENTRY,
            prepared.database to DATABASE_ENTRY,
        ) + prepared.mediaFiles.map { it to "$MEDIA_PREFIX${it.name}" }
        var written = 0L
        for ((file, name) in files) {
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "媒体备份文件被替换或无法读取：$name"
            }
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(written <= MAX_UNPACKED_BYTES - count) {
                        "媒体 ZIP 导出数据超过上限"
                    }
                    zip.write(buffer, 0, count)
                    written += count
                }
            }
            zip.closeEntry()
        }
        // A concurrent write/symlink swap of staged bytes must not be silently
        // reported as a valid ZIP. The caller handles partial output on errors.
        verifier.validate(staging)
        return written
    }

    /** Standalone transport for staging tests and a future full-archive writer. */
    fun export(staging: File, output: OutputStream): Long =
        ZipOutputStream(output.buffered()).use { zip -> writeEntries(staging, zip) }

    /**
     * Extract to a new isolated directory only. No SQLite or original media is
     * installed here, so invalid or truncated ZIPs cannot affect user records.
     */
    fun stage(input: InputStream, target: File): LocalMediaStudioBackupSnapshot.Prepared {
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "媒体备份暂存目录已存在，拒绝覆盖"
        }
        check(target.mkdirs()) { "无法创建媒体备份恢复隔离目录" }
        try {
            // ZIP does not serialize empty directories. An empty Studio (or one
            // with only remote links) is a valid archive with zero local files.
            check(File(target, "media").mkdirs()) { "无法建立媒体成果暂存目录" }
            val entries = linkedSetOf<String>()
            var bytes = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val mediaName = if (name.startsWith(MEDIA_PREFIX))
                        name.removePrefix(MEDIA_PREFIX) else null
                    val isMedia = mediaName != null && validMediaName(mediaName)
                    require(!entry.isDirectory &&
                        (name == MANIFEST_ENTRY || name == DATABASE_ENTRY || isMedia) &&
                        entries.add(name) && entries.size <= MAX_FILES + 2) {
                        "媒体备份 ZIP 含未知、重复、路径越界或过量文件：$name"
                    }
                    val output = when (name) {
                        MANIFEST_ENTRY -> File(target, "media-studio.manifest")
                        DATABASE_ENTRY -> File(target, "media-studio.sqlite")
                        else -> File(target, "media/${requireNotNull(mediaName)}")
                    }
                    output.parentFile?.mkdirs()
                    output.outputStream().buffered().use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            check(bytes <= MAX_UNPACKED_BYTES - n) {
                                "媒体备份 ZIP 解压体积超过上限"
                            }
                            bytes += n
                            entryBytes += n
                            if (name == MANIFEST_ENTRY) {
                                check(entryBytes <= MAX_MANIFEST_BYTES) {
                                    "媒体备份 ZIP 清单过大"
                                }
                            }
                            // Space reserve is rechecked as the payload grows;
                            // do not create a restored database on a nearly full disk.
                            if (bytes % SPACE_CHECK_INTERVAL < n) check(target.usableSpace > FREE_SPACE_RESERVE) {
                                "空间不足，无法安全暂存媒体备份"
                            }
                            stream.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(MANIFEST_ENTRY in entries && DATABASE_ENTRY in entries) {
                "媒体备份缺少数据库或清单"
            }
            return LocalMediaStudioBackupSnapshot(context, json).validate(target)
        } catch (failure: Exception) {
            target.deleteRecursively()
            throw failure
        }
    }

    private fun validMediaName(name: String): Boolean =
        NAME.matches(name) && name != "." && name != ".." && !name.startsWith('.')

    private companion object {
        const val ROOT = "studio/"
        const val MANIFEST_ENTRY = ROOT + "media-studio.manifest"
        const val DATABASE_ENTRY = ROOT + "media-studio.sqlite"
        const val MEDIA_PREFIX = ROOT + "media/"
        const val MAX_FILES = 100_000
        const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
        const val MAX_UNPACKED_BYTES = 64L * 1024L * 1024L * 1024L
        const val SPACE_CHECK_INTERVAL = 8L * 1024L * 1024L
        const val FREE_SPACE_RESERVE = 64L * 1024L * 1024L
        val NAME = Regex("[A-Za-z0-9._-]{1,180}")
    }
}
