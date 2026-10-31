package com.labteto.dshmobile.local.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * App-startup reclamation of strictly identified, reproducible intermediate files.
 *
 * Durable write-in-progress chunks, session records, user-created workspace files,
 * published attachments, and tool outputs belong to their respective owners and
 * are never eligible for this sweep.
 *
 * Called on Dispatchers.IO after process start, not on the UI thread and not on a
 * periodic timer. Temporary files are only removed after a full day of inactivity.
 */
internal class LocalWorkspaceIntermediateMaintenance(private val workspaceRoot: File) {
    data class Report(
        val deletedFiles: Int,
        val deletedBytes: Long,
        val scannedEntries: Int,
        val remainingCacheBytes: Long,
    )

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val TEMP_GRACE_MS = DAY_MS
        const val INDEX_RETENTION_MS = 14L * DAY_MS
        const val INDEX_PRESSURE_GRACE_MS = DAY_MS
        const val INDEX_BUDGET = 64L * 1024 * 1024
        const val MAX_VISITS = 30_000
        val INDEX_FILE = Regex("^[0-9a-f]{64}\\.index$")
        val INDEX_TEMP = Regex("^\\.[0-9a-f]{64}\\.index-[0-9a-fA-F-]{36}\\.tmp$")
        val ATTACHMENT_TEMP = Regex("^\\.(?:incoming-[0-9a-fA-F-]{36}|normalized-[0-9a-fA-F-]{36}\\.jpg)$")
        val WRITE_TEMP = Regex("^\\.write-[a-zA-Z0-9-]{6,}\\.tmp$")
    }

    fun cleanup(nowMillis: Long = System.currentTimeMillis()): Report {
        val root = workspaceRoot.canonicalFile
        if (!root.isDirectory) return Report(0, 0L, 0, 0L)
        require(!Files.isSymbolicLink(workspaceRoot.toPath())) { "工作区根目录是符号链接，拒绝清理" }
        var deleted = 0
        var bytes = 0L
        var scanned = 0
        var indexBytes = 0L

        fun stale(file: File, ageMillis: Long): Boolean =
            file.lastModified() > 0L && file.lastModified() <= nowMillis &&
                nowMillis - file.lastModified() >= ageMillis

        fun regular(file: File): Boolean =
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

        fun deleteOwned(file: File) {
            check(regular(file)) { "缓存文件在清理期间发生变化：${file.name}" }
            val length = file.length()
            check(file.delete()) { "无法删除过期中间产物：${file.path}" }
            deleted++
            bytes += length
        }

        // Only the two exact index directories have reconstructible .index files.
        val indexDirs = listOf(".dsh/text-index", ".dsh/archive-index")
        val retained = mutableListOf<File>()
        for (relative in indexDirs) {
            val dir = File(root, relative)
            if (!dir.exists()) continue
            require(!Files.isSymbolicLink(dir.toPath()) &&
                dir.canonicalFile.toPath().startsWith(root.toPath())) {
                "缓存目录不安全：$relative"
            }
            require(dir.isDirectory) { "索引缓存路径不是目录：$relative" }
            Files.newDirectoryStream(dir.toPath()).use { entries ->
             for (entry in entries) {
                val candidate = entry.toFile()
                if (++scanned > MAX_VISITS) break
                if (!regular(candidate)) continue
                when {
                    INDEX_TEMP.matches(candidate.name) && stale(candidate, TEMP_GRACE_MS) ->
                        deleteOwned(candidate)
                    INDEX_FILE.matches(candidate.name) && stale(candidate, INDEX_RETENTION_MS) ->
                        deleteOwned(candidate)
                    INDEX_FILE.matches(candidate.name) -> {
                        retained += candidate
                        indexBytes += candidate.length()
                    }
                }
             }
            }
        }
        // Pressure eviction is LRU by last indexed use and never targets recently built data.
        if (indexBytes > INDEX_BUDGET) {
            for (candidate in retained.sortedBy(File::lastModified)) {
                if (indexBytes <= INDEX_BUDGET) break
                if (!regular(candidate) || !stale(candidate, INDEX_PRESSURE_GRACE_MS)) continue
                val length = candidate.length()
                deleteOwned(candidate)
                indexBytes = (indexBytes - length).coerceAtLeast(0L)
            }
        }

        // Importer staging names are private and never refer to published attachments.
        val attachments = File(root, ".dsh/attachments")
        if (attachments.exists()) {
            require(!Files.isSymbolicLink(attachments.toPath()) &&
                attachments.canonicalFile.toPath().startsWith(root.toPath())) {
                "附件暂存目录不安全"
            }
            require(attachments.isDirectory) { "附件暂存路径不是目录" }
            Files.newDirectoryStream(attachments.toPath()).use { entries ->
              for (entry in entries) {
                val candidate = entry.toFile()
                if (++scanned > MAX_VISITS) break
                if (ATTACHMENT_TEMP.matches(candidate.name) && regular(candidate) &&
                    stale(candidate, TEMP_GRACE_MS)) deleteOwned(candidate)
              }
            }
        }

        // Reclaim only the app-owned write-temp directory. Never delete matching
        // names in user source trees; filename patterns alone prove no ownership.
        val writes = File(root, ".dsh/write-temp")
        if (writes.exists()) {
            require(!Files.isSymbolicLink(writes.toPath()) &&
                writes.canonicalFile.toPath().startsWith(root.toPath())) {
                "写入缓存目录不安全"
            }
            require(writes.isDirectory) { "写入缓存路径不是目录" }
            Files.newDirectoryStream(writes.toPath()).use { entries ->
                for (entry in entries) {
                    if (++scanned > MAX_VISITS) break
                    val candidate = entry.toFile()
                    if (WRITE_TEMP.matches(candidate.name) && regular(candidate) &&
                        stale(candidate, TEMP_GRACE_MS)) deleteOwned(candidate)
                }
            }
        }
        return Report(deleted, bytes, scanned, indexBytes)
    }
}
