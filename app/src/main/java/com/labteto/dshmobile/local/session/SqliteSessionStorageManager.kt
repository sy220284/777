package com.labteto.dshmobile.local.session

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * New SQLite-only session storage administration. No legacy JSONL/JSON export,
 * restore or compaction path can silently omit the authoritative SQLite events.
 *
 * Both copies are consistent per database (VACUUM INTO). The event archive is the
 * fact authority; session snapshots are reconstructible, so they need not share
 * one cross-database write transaction.
 */
internal class SqliteSessionStorageManager(
    private val context: Context,
    private val snapshots: SqliteSessionSnapshotStore,
    private val events: SqliteSessionEventDatabase,
    private val snapshotDatabaseName: String = "local_session_snapshots_v1.db",
    private val eventDatabaseName: String = "local_session_events_v1.db",
    private val restoreKey: String = "default",
) {
    private val pendingRoot get() = File(context.filesDir, "local-harness/.sqlite-restore-pending-$restoreKey")
    private val snapshotFile get() = context.getDatabasePath(snapshotDatabaseName)
    private val eventFile get() = context.getDatabasePath(eventDatabaseName)
    private val mediaBackup get() = SqliteSessionMediaArchive(context)

    fun status(): LocalSessionStorageStatus {
        val files = listOf(snapshotFile, eventFile).flatMap { source ->
            listOf(source, File(source.path + "-wal"), File(source.path + "-shm"))
        }
        return LocalSessionStorageStatus(
            totalBytes = files.sumOf { if (it.isFile) it.length() else 0L },
            freeBytes = context.filesDir.usableSpace,
            budgetBytes = LocalSessionStorageStatus.DEFAULT_BUDGET_BYTES,
            sessionCount = snapshots.readSummaries().size,
            rawSegmentCount = 0,
            compressedSegmentCount = 0,
        )
    }

    suspend fun compactAll(): LocalSessionStorageStatus {
        val maintenance = SqliteSessionConsistencyGate.acquireExclusive()
        try {
        listOf(snapshots.writableDatabase, events.writableDatabase).forEach { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { result ->
                if (result.moveToFirst()) {
                    check(result.getInt(0) == 0) { "SQLite WAL 仍被活动读者占用" }
                }
            }
            db.execSQL("VACUUM")
        }
        return status()
        } finally { maintenance.unlock() }
    }

    fun exportAll(output: OutputStream): Long {
        val staging = File(context.cacheDir, "sqlite-session-export-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法创建 SQLite 备份工作目录" }
        val snapshotCopy = File(staging, SNAPSHOT_ENTRY)
        val eventCopy = File(staging, EVENT_ENTRY)
        try {
            // Freeze the pair during both SQLite snapshots only. Slow ZIP/USB
            // streaming must not block fresh messages or Work event writes.
            val archiveLease = SqliteSessionConsistencyGate.acquireExclusive()
            try {
                backup(snapshots.readableDatabase, snapshotCopy)
                backup(events.readableDatabase, eventCopy)
            } finally {
                archiveLease.unlock()
            }
            validateSnapshotEventCursors(snapshotCopy, eventCopy)
            var written = 0L
            // ZIP bytes stream to the destination; no whole-archive memory buffer.
            ZipOutputStream(output.buffered()).use { zip ->
                listOf(snapshotCopy, eventCopy).forEach { source ->
                    zip.putNextEntry(ZipEntry(source.name))
                    source.inputStream().buffered().use { input ->
                        written += input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
                written += mediaBackup.export(zip, eventCopy)
                check(written <= MAX_UNPACKED_BYTES) {
                    "完整 SQLite 媒体备份解压体积超过当前恢复上限"
                }
            }
            return written
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun backup(db: SQLiteDatabase, destination: File) {
        require(!destination.exists()) { "备份目标已存在" }
        // Android 16+ / SQLite 3.27+: a consistent WAL-aware live snapshot.
        db.execSQL("VACUUM INTO ?", arrayOf(destination.absolutePath))
        validateDatabase(destination)
    }

    /** Compatibility entry for existing in-memory tests and callers. */
    fun stageRestore(archive: ByteArray): Int {
        require(archive.isNotEmpty() && archive.size <= MAX_ZIP_BYTES) { "SQLite 备份压缩包为空或超限" }
        return stageRestore(ByteArrayInputStream(archive))
    }

    /** Stage SAF/file backups directly from a stream, independent of ZIP size. */
    @Synchronized
    fun stageRestore(archive: InputStream): Int {
        require(!pendingRoot.exists()) { "已有待恢复数据，请重启应用处理" }
        check(snapshots.readSummaries().isEmpty() && restorableEmptyEditor(events.readableDatabase)) {
            "为防止覆盖现有会话，SQLite 备份目前只允许恢复到空会话库"
        }
        val staging = File(context.filesDir, "local-harness/.sqlite-restore-${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法建立 SQLite 恢复隔离目录" }
        try {
            val names = linkedSetOf<String>()
            var bytes = 0L
            var lastFreeSpaceCheck = 0L
            ZipInputStream(archive).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val mediaEntry = entry.name.startsWith(SqliteSessionMediaArchive.MEDIA_PREFIX) &&
                        SqliteSessionMediaArchive.validFilename(
                            entry.name.removePrefix(SqliteSessionMediaArchive.MEDIA_PREFIX))
                    require(!entry.isDirectory &&
                        (entry.name == SNAPSHOT_ENTRY || entry.name == EVENT_ENTRY ||
                            entry.name == SqliteSessionMediaArchive.MANIFEST_ENTRY || mediaEntry) &&
                        names.add(entry.name)) { "SQLite 备份含未知或重复文件" }
                    val target = if (mediaEntry) File(
                        staging, SqliteSessionMediaArchive.STAGED_MEDIA_DIR + "/" +
                            entry.name.removePrefix(SqliteSessionMediaArchive.MEDIA_PREFIX),
                    ) else File(staging, entry.name)
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            bytes += n
                            require(bytes <= MAX_UNPACKED_BYTES) { "SQLite 备份解压数据超限" }
                            // Large stream imports must leave room for SQLite recovery and app activity.
                            if (bytes - lastFreeSpaceCheck >= 8L * 1024L * 1024L) {
                                check(staging.usableSpace > 64L * 1024L * 1024L) {
                                    "本机空闲空间不足，无法安全恢复完整媒体备份"
                                }
                                lastFreeSpaceCheck = bytes
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                    zip.closeEntry()
                    if (entry.name == SNAPSHOT_ENTRY || entry.name == EVENT_ENTRY)
                        validateDatabase(target)
                }
            }
            require(SNAPSHOT_ENTRY in names && EVENT_ENTRY in names) {
                "SQLite 备份缺少快照数据库或事件数据库"
            }
            // The legacy two-DB-only archive is incomplete when it contains
            // image references; never silently treat that as a full media restore.
            require(SqliteSessionMediaArchive.MANIFEST_ENTRY in names) {
                "旧 SQLite 备份不含图片原文件；请导出完整媒体备份"
            }
            mediaBackup.validateStaged(staging, names)
            validateTables(File(staging, SNAPSHOT_ENTRY), "session_snapshots")
            validateTables(File(staging, EVENT_ENTRY), "session_events")
            validateSnapshotEventCursors(
                File(staging, SNAPSHOT_ENTRY), File(staging, EVENT_ENTRY),
            )
            File(staging, "READY").writeText("sqlite-v2-media")
            check(staging.renameTo(pendingRoot)) { "SQLite 备份无法提交恢复计划" }
            return countRows(File(pendingRoot, SNAPSHOT_ENTRY), "session_snapshots")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Called before Session adapters open SQLite connections, on the NEXT process
     * launch. A partial install is idempotently replayed from unchanged staged copies.
     */
    @Synchronized
    fun applyPendingRestore(): Int {
        val ready = File(pendingRoot, "READY")
        if (!ready.isFile) return 0
        check(ready.readText() == "sqlite-v2-media") { "未知的 SQLite 恢复版本" }
        val sources = listOf(
            File(pendingRoot, SNAPSHOT_ENTRY) to snapshotFile,
            File(pendingRoot, EVENT_ENTRY) to eventFile,
        )
        sources.forEach { (src, dst) ->
            validateDatabase(src)
            // Never replace a live/user-owned database. Already installed bytes
            // are safe only when identical to the staged, verified source.
            if (dst.exists()) {
                // A killed process can leave a valid EMPTY WAL database with sidecars.
                // Let SQLite recover/checkpoint it; never discard a WAL before reading it.
                settleStoppedWal(dst)
                if (!sameBytes(src, dst)) {
                    require(isEmptyDatabase(dst)) { "已有 SQLite 会话数据，恢复拒绝覆盖" }
                }
            }
        }
        val mediaNames = buildSet {
            add(SNAPSHOT_ENTRY)
            add(EVENT_ENTRY)
            add(SqliteSessionMediaArchive.MANIFEST_ENTRY)
            File(pendingRoot, SqliteSessionMediaArchive.STAGED_MEDIA_DIR)
                .listFiles().orEmpty().forEach { add(SqliteSessionMediaArchive.MEDIA_PREFIX + it.name) }
        }
        mediaBackup.validateStaged(pendingRoot, mediaNames)
        // Install and hash-verify all attachments before committing a SQLite DB
        // that may reference them. Retrying after process death is idempotent.
        mediaBackup.installStaged(pendingRoot)
        sources.forEach { (src, dst) ->
            if (dst.exists() && sameBytes(src, dst)) return@forEach
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".restore-tmp")
            if (tmp.exists()) check(tmp.delete())
            src.copyTo(tmp, overwrite = false)
            check(tmp.renameTo(dst)) { "SQLite 备份恢复无法完成：${dst.name}" }
        }
        val count = countRows(File(pendingRoot, SNAPSHOT_ENTRY), "session_snapshots")
        check(pendingRoot.deleteRecursively()) { "SQLite 数据已恢复，但待恢复标记未能删除" }
        return count
    }

    /**
     * A backup whose snapshot cursor is ahead of its event authority is unsafe:
     * replay after restore could repeat effects or omit committed messages.
     * Validate user-imported ZIPs as well as locally exported pairs.
     */
    private fun validateSnapshotEventCursors(snapshotCopy: File, eventCopy: File) {
        SQLiteDatabase.openDatabase(
            snapshotCopy.path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { snapshotsDb ->
            SQLiteDatabase.openDatabase(
                eventCopy.path, null, SQLiteDatabase.OPEN_READONLY,
            ).use { eventsDb ->
                snapshotsDb.rawQuery(
                    "SELECT id,payload FROM session_snapshots", null,
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val id = rows.getString(0)
                        val payload = Json.parseToJsonElement(rows.getString(1)).jsonObject
                        val latest = eventsDb.rawQuery(
                            "SELECT next_sequence FROM session_event_cursors WHERE session_id=?",
                            arrayOf(id),
                        ).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getLong(0) - 1L else -1L
                        }
                        listOf(
                            "transcriptProjectedThroughSequence",
                            "controlProjectedThroughSequence",
                        ).forEach { key ->
                            val value = (payload[key] as? JsonPrimitive)?.longOrNull
                            check(value == null || value in -1L..latest) {
                                "SQLite 备份会话 $id 的 $key 游标越界：$value（日志末尾 $latest）"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun validateDatabase(file: File) {
        require(file.isFile && file.length() >= 100L) { "SQLite 备份数据库损坏" }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA quick_check", null).use { result ->
                check(result.moveToFirst() && result.getString(0) == "ok") {
                    "SQLite 备份完整性校验失败"
                }
            }
        }
    }

    private fun validateTables(file: File, table: String) {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { rows -> require(rows.moveToFirst()) { "SQLite 备份缺少必要数据表 $table" } }
        }
    }

    private fun countRows(file: File, table: String): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { rows ->
                if (rows.moveToFirst()) rows.getInt(0) else 0
            }
        }

    private fun hasRegisteredAssets(db: SQLiteDatabase): Boolean {
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='local_assets'", null,
        ).use { it.moveToFirst() }
        if (!exists) return false
        return db.rawQuery("SELECT 1 FROM local_assets LIMIT 1", null).use { it.moveToFirst() }
    }

    private fun settleStoppedWal(file: File) {
        if (!File(file.path + "-wal").exists() && !File(file.path + "-shm").exists()) return
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { result ->
                check(result.moveToFirst() && result.getInt(0) == 0) {
                    "恢复前 SQLite 仍被写入者占用"
                }
            }
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { result ->
                check(result.moveToFirst() && result.getString(0).equals("delete", true)) {
                    "恢复前 SQLite WAL 未完成安全关闭"
                }
            }
        }
        check(!File(file.path + "-wal").exists()) {
            "SQLite WAL 已结算但仍存在，拒绝覆盖数据库"
        }
    }

    /**
     * A deleted final Session can leave an unstarted editor's model-selection
     * event and orphan asset catalog rows. Those are not conversation facts.
     * Keep every original media file: physical GC remains disabled.
     */
    private fun restorableEmptyEditor(db: SQLiteDatabase): Boolean {
        val onlySelection = db.rawQuery(
            "SELECT 1 FROM session_events WHERE event_type != ? LIMIT 1",
            arrayOf(com.labteto.dshmobile.local.model.LOCAL_SESSION_MODEL_SELECTION_EVENT),
        ).use { !it.moveToFirst() }
        if (!onlySelection) return false
        val referencesPresent = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='local_asset_references'",
            null,
        ).use { it.moveToFirst() }
        return !referencesPresent || db.rawQuery(
            "SELECT 1 FROM local_asset_references LIMIT 1", null,
        ).use { !it.moveToFirst() }
    }

    private fun isEmptyDatabase(file: File): Boolean {
        if (!file.exists()) return true
        // Only a recognized, genuinely empty session database may be replaced.
        // An arbitrary SQLite file with other tables must never count as empty.
        val requiredTable = when (file.name) {
            snapshotDatabaseName -> "session_snapshots"
            eventDatabaseName -> "session_events"
            else -> return false
        }
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(requiredTable),
                ).use { exists ->
                    if (!exists.moveToFirst()) false else {
                        db.rawQuery("SELECT 1 FROM $requiredTable LIMIT 1", null).use { rows ->
                            !rows.moveToFirst() ||
                                (requiredTable == "session_events" && restorableEmptyEditor(db))
                        }
                    }
                }
            }
        }.getOrDefault(false)
    }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        fun digest(f: File): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256")
            f.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    hash.update(buffer, 0, n)
                }
            }
            return hash.digest()
        }
        return digest(a).contentEquals(digest(b))
    }

    private companion object {
        const val SNAPSHOT_ENTRY = "snapshot.sqlite"
        const val EVENT_ENTRY = "events.sqlite"
        const val MAX_ZIP_BYTES = 128 * 1024 * 1024
        // Bounded for malformed ZIP protection; streaming avoids RAM proportional to archive size.
        const val MAX_UNPACKED_BYTES = 64L * 1024L * 1024L * 1024L
    }
}
