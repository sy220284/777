package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.harness.session.SessionEventFileSnapshot
import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.VersionedSessionStore
import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.JsonObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * User-facing governance for append-only local Session Event storage.
 *
 * Raw facts are never deleted here. The manager can compress immutable legacy segments and export
 * the complete local session directory. Reclaiming retained history remains an explicit user
 * action through session deletion after export.
 */
internal class LocalSessionStorageManager(
    private val root: File,
    private val json: Json,
    private val eventSnapshotOpener: (File, Json) -> List<SessionEventFileSnapshot> = { activeFile, codec ->
        SessionEventLog(activeFile, codec).openDurableFileSnapshot()
    },
) {
    fun status(): LocalSessionStorageStatus {
        val files = durableFiles()
        val rawSegments = files.count { isRawSegment(it.getName()) }
        val compressedSegments = files.count { isCompressedSegment(it.getName()) }
        val sessionIds = files.mapNotNull(::sessionIdOf).toSet()
        return LocalSessionStorageStatus(
            totalBytes = files.sumOf { it.length() },
            freeBytes = root.getUsableSpace(),
            budgetBytes = LocalSessionStorageStatus.DEFAULT_BUDGET_BYTES,
            sessionCount = sessionIds.size,
            rawSegmentCount = rawSegments,
            compressedSegmentCount = compressedSegments,
        )
    }

    /**
     * Compress every legacy rotated segment, including immutable segments belonging to the active
     * session. SessionEventLog serializes each path through a shared path lock, so this remains safe
     * while an append is in flight.
     */
    suspend fun compactAll(): LocalSessionStorageStatus {
        val ids = durableFiles().mapNotNull(::sessionIdOf).distinct()
        for (id in ids) {
            val log = SessionEventLog(File(root, "$id.events.jsonl"), json)
            while (log.archiveLegacySegments(limit = 16) > 0) {
                delay(COMPACTION_YIELD_MILLIS)
            }
        }
        return status()
    }

    /**
     * Export a lossless snapshot of all durable session files. Each source file is copied directly
     * into the ZIP; the source directory is never modified.
     */
    fun exportAll(output: OutputStream): Long {
        var exportedBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            // Session snapshots are disposable acceleration state; Session Event files are the
            // source of truth and therefore copied under the same lock used by append/rotation.
            durableFiles()
                .filter { source -> source.getName().endsWith(".json") }
                .sortedBy(File::getName)
                .forEach { source -> exportedBytes += copyZipEntry(source, zip) }

            durableFiles()
                .mapNotNull(::sessionIdOf)
                .distinct()
                .sorted()
                .forEach { id ->
                    val snapshot = eventSnapshotOpener(File(root, "$id.events.jsonl"), json)
                    try {
                        snapshot.forEach { source ->
                            exportedBytes += copyZipEntry(
                                name = source.name,
                                lastModified = source.lastModified,
                                length = source.length,
                                input = source.input,
                                zip = zip,
                            )
                        }
                    } finally {
                        snapshot.forEach { source ->
                            runCatching { source.close() }
                        }
                    }
                }
        }
        return exportedBytes
    }

    /**
     * Stage a user-chosen ZIP without touching running sessions. Only session IDs not already
     * on this device are eligible. Runtime loads the staged archive before repositories start
     * on the NEXT app process startup; this never hot-overwrites active event files.
     */
    @Synchronized
    fun stageRestore(archive: ByteArray): Int {
        require(archive.isNotEmpty() && archive.size <= MAX_RESTORE_ZIP_BYTES) {
            "备份 ZIP 过大或为空（最大 64 MiB）"
        }
        val pending = File(root, ".restore-pending")
        require(!pending.exists()) { "已有一个待生效的会话恢复，请先重启应用" }
        val staging = File(root, ".restore-building-" + UUID.randomUUID())
        check(staging.mkdirs()) { "无法创建会话恢复隔离目录" }
        try {
            val names = linkedSetOf<String>()
            var totalBytes = 0L
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && SAFE_RESTORE_NAME.matches(name)) {
                        "备份中含不允许的文件名称"
                    }
                    require(names.add(name) && names.size <= MAX_RESTORE_ENTRIES) {
                        "备份包含重复文件或文件数量超限"
                    }
                    val target = File(staging, name)
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            entryBytes += count
                            require(totalBytes <= MAX_RESTORE_UNPACKED_BYTES &&
                                entryBytes <= MAX_RESTORE_ENTRY_BYTES) {
                                "备份解压数据超出安全上限"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry() // Enforce ZIP CRC for every file.
                }
            }
            require(names.isNotEmpty()) { "备份中没有可恢复的会话文件" }
            val existing = durableFiles().mapNotNull(::sessionIdOf).toSet()
            val grouped = names.groupBy { name -> sessionIdOf(File(name))!! }
            val eligible = grouped.filterKeys { it !in existing }
            require(eligible.isNotEmpty()) { "全部会话已存在，恢复不会覆盖当前数据" }
            // Never stage a snapshot without its append-only event source.
            require(eligible.all { (id, fileNames) ->
                fileNames.any { it.startsWith("$id.events.jsonl") } &&
                    fileNames.any { it == "$id.json" || it == "$id.backup.json" }
            }) { "备份缺少会话快照或事件源，已拒绝导入" }
            grouped.filterKeys { it in existing }.values.flatten().forEach { File(staging, it).delete() }
            eligible.values.flatten().forEach { validateRestoredFile(File(staging, it)) }
            File(staging, "READY").writeText("v1")
            check(staging.renameTo(pending)) { "无法提交恢复计划，请核对剩余存储空间" }
            return eligible.size
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Apply before LocalSessionRepository / event readers are initialized. Replaying after a
     * crash is idempotent: already copied bytes are checked, and no existing data is replaced.
     * Staged sources are retained until all destinations have been verified.
     */
    @Synchronized
    fun applyPendingRestore(): Int {
        val pending = File(root, ".restore-pending")
        if (!pending.exists() || !File(pending, "READY").isFile) return 0
        val sources = pending.listFiles().orEmpty().filter { it.isFile && it.name != "READY" }
        require(sources.isNotEmpty()) { "待恢复备份的文件清单为空" }
        require(sources.all { SAFE_RESTORE_NAME.matches(it.name) }) { "待恢复的文件路径非法" }
        var committed = 0
        for (source in sources.sortedBy { it.name }) {
            val destination = File(root, source.name)
            if (destination.exists()) {
                require(destination.isFile && hashesEqual(source, destination)) {
                    "本地会话与待恢复备份重名，已中止以避免覆盖"
                }
            } else {
                val temporary = File(root, source.name + ".tmp")
                if (temporary.exists()) require(temporary.delete()) { "无法清理上次中断的临时文件" }
                source.copyTo(temporary, overwrite = false)
                check(temporary.renameTo(destination)) { "恢复写入失败；下次启动将继续未完成的文件" }
            }
            committed++
        }
        // A pre-existing valid catalog does not discover copied files. Register only
        // restored snapshot identities, after every file copy has been verified.
        val catalog = VersionedSessionStore(root, json)
        sources.mapNotNull(::sessionIdOf).toSet().forEach(catalog::adoptRestored)
        check(pending.deleteRecursively()) { "已恢复，但无法清理隔离区" }
        return committed
    }

    private fun validateRestoredFile(file: File) {
        when {
            file.name.endsWith(".json") -> {
                require(json.parseToJsonElement(file.readText()) is JsonObject) {
                    "会话快照 JSON 格式无效"
                }
            }
            file.name.endsWith(".gz") -> {
                // Validate nested gzip integrity and cap its own expanded bytes.
                GZIPInputStream(file.inputStream().buffered()).use { input ->
                    val buf = ByteArray(32 * 1024)
                    var length = 0L
                    while (true) {
                        val count = input.read(buf)
                        if (count < 0) break
                        length += count
                        require(length <= MAX_RESTORE_UNPACKED_BYTES) {
                            "会话压缩事件解码后超过安全上限"
                        }
                    }
                }
            }
            file.name.contains(".events.jsonl") -> {
                file.forEachLine { line ->
                    if (line.isNotBlank()) require(json.parseToJsonElement(line) is JsonObject) {
                        "备份包含损坏的会话事件 JSON"
                    }
                }
            }
        }
    }

    private fun hashesEqual(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        fun hash(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }
        return hash(a).contentEquals(hash(b))
    }

    private fun copyZipEntry(source: File, zip: ZipOutputStream): Long =
        source.inputStream().buffered().use { input ->
            copyZipEntry(
                name = source.getName(),
                lastModified = source.lastModified(),
                length = source.length(),
                input = input,
                zip = zip,
            )
        }

    private fun copyZipEntry(
        name: String,
        lastModified: Long,
        length: Long,
        input: InputStream,
        zip: ZipOutputStream,
    ): Long {
        var copied = 0L
        var remaining = length.coerceAtLeast(0L)
        val entry = ZipEntry(name).also { target ->
            target.setTime(lastModified)
        }
        zip.putNextEntry(entry)
        try {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (remaining > 0L) {
                val requested = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, requested)
                check(read >= 0) { "导出会话事件时源文件意外截断：$name" }
                zip.write(buffer, 0, read)
                copied += read
                remaining -= read
            }
        } finally {
            zip.closeEntry()
        }
        return copied
    }

    private fun durableFiles(): List<File> =
        root.listFiles().orEmpty()
            .filter { file ->
                file.isFile &&
                    !file.getName().endsWith(".tmp") &&
                    !file.getName().contains(".corrupt-") &&
                    (
                        file.getName().endsWith(".json") ||
                            file.getName().contains(".events.jsonl")
                    )
            }

    private fun sessionIdOf(file: File): String? {
        val name = file.name
        if (".events.jsonl" in name) {
            return name.substringBefore(".events.jsonl").takeIf(RESTORE_SESSION_ID::matches)
        }
        if (!name.endsWith(".json")) return null
        val base = name.removeSuffix(".json")
        // A backup or migration checkpoint belongs to its Session, not a new
        // Session named 'id.backup' or 'id.checkpoint-v0'. Dotted IDs remain legal.
        val candidates = buildList {
            if (base.endsWith(".backup")) add(base.removeSuffix(".backup"))
            CHECKPOINT_BASENAME.matchEntire(base)?.let { add(it.groupValues[1]) }
            add(base)
        }.distinct().filter(RESTORE_SESSION_ID::matches)
        val withEvents = candidates.filter { File(file.parentFile, "$it.events.jsonl").exists() }
        if (withEvents.size == 1) return withEvents.single()
        if (withEvents.size > 1) {
            val embedded = runCatching {
                json.parseToJsonElement(file.readText()).jsonObject["id"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return withEvents.firstOrNull { it == embedded } ?: withEvents.first()
        }
        return candidates.firstOrNull()
    }

    private fun isRawSegment(name: String): Boolean =
        RAW_SEGMENT.matches(name)

    private fun isCompressedSegment(name: String): Boolean =
        COMPRESSED_SEGMENT.matches(name)

    private companion object {
        val RAW_SEGMENT = Regex("^.+\\.events\\.jsonl\\.part-[0-9]+$")
        val COMPRESSED_SEGMENT = Regex("^.+\\.events\\.jsonl\\.part-[0-9]+\\.gz$")
        const val COMPACTION_YIELD_MILLIS = 25L
        const val COPY_BUFFER_BYTES = 32 * 1024
        private val RESTORE_SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val CHECKPOINT_BASENAME = Regex("^(.*)\\.checkpoint-v[0-9]+$")
        private val SAFE_RESTORE_NAME = Regex("^[A-Za-z0-9._-]{1,128}\\.(?:json|backup\\.json|checkpoint-v[0-9]+\\.json|events\\.jsonl(?:\\.part-[0-9]+(?:\\.gz)?)?)$")
        private const val MAX_RESTORE_ZIP_BYTES = 64 * 1024 * 1024
        private const val MAX_RESTORE_ENTRY_BYTES = 64L * 1024 * 1024
        private const val MAX_RESTORE_UNPACKED_BYTES = 256L * 1024 * 1024
        private const val MAX_RESTORE_ENTRIES = 2_000
    }
}
