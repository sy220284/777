package com.labteto.dshmobile.local.attachment

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.labteto.dshmobile.local.session.SqliteSessionConsistencyGate
import com.labteto.dshmobile.local.session.SqliteSessionEventDatabase
import com.labteto.dshmobile.local.session.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Incrementally populated local asset metadata catalog, stored IN the authoritative
 * event database so existing WAL backups also copy these tables.
 *
 * Unresolved references are retained separately until their original file bytes
 * have been verified. The directory is not a physical GC authority until ALL
 * historical/live references and mutation paths have been reconciled.
 */
internal class SqliteLocalAssetCatalog(
    private val events: SqliteSessionEventDatabase,
) {
    data class Asset(
        val id: String,
        val path: String,
        val mediaType: String,
        val bytes: Long,
        val state: String,
    )

    fun register(imported: LocalImportedAttachment) {
        val id = requireNotNull(imported.attachmentId) { "附件缺少内容哈希" }
        require(SHA256.matches(id)) { "附件哈希不合法" }
        require(imported.relativePath.matches(Regex(
            "^\\.dsh/attachments/$id(?:\\.[a-z0-9]{1,10})?$"
        ))) { "附件相对路径与内容哈希不一致" }
        require(imported.bytes >= 0L && imported.mediaType.length in 1..100 &&
            imported.mediaType.none { it == '\n' || it == '\r' }) { "附件元数据非法" }
        val guard = SqliteSessionConsistencyGate.acquireMutation()
        try {
            val db = events.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val previous = readAsset(db, id)
                if (previous != null) {
                    // Hash identifies the content. A byte-identical attachment
                    // may arrive as foo.txt or foo.csv; the visible MIME/path
                    // belongs to its individual verified location.
                    check(previous.bytes == imported.bytes) {
                        "同一资源哈希出现不一致的字节数"
                    }
                } else {
                    db.insertOrThrow("local_assets", null, ContentValues().apply {
                        put("asset_id", id)
                        put("relative_path", imported.relativePath)
                        put("media_type", imported.mediaType)
                        put("byte_size", imported.bytes)
                        put("storage_kind", "file")
                        put("state", "present")
                        put("created_at", System.currentTimeMillis())
                    })
                }
                // Never erase the first path. Every published path must be
                // represented, including distinct extensions of identical bytes.
                db.insertWithOnConflict(
                    "local_asset_locations", null, ContentValues().apply {
                        put("relative_path", imported.relativePath)
                        put("asset_id", id)
                        put("media_type", imported.mediaType)
                        put("byte_size", imported.bytes)
                    }, SQLiteDatabase.CONFLICT_IGNORE,
                )
                val registered = db.rawQuery(
                    "SELECT asset_id,byte_size,media_type FROM local_asset_locations WHERE relative_path=?",
                    arrayOf(imported.relativePath),
                ).use { rows ->
                    check(rows.moveToFirst()) { "附件路径登记失败" }
                    Triple(rows.getString(0), rows.getLong(1), rows.getString(2))
                }
                check(registered.first == id && registered.second == imported.bytes) {
                    "附件路径登记与内容哈希冲突"
                }
                if (registered.third != imported.mediaType) {
                    // A recovered file's MIME was guessed from its extension.
                    // The later actual importer can refine the presentation
                    // metadata without changing the content-addressed identity.
                    check(db.update("local_asset_locations", ContentValues().apply {
                        put("media_type", imported.mediaType)
                    }, "relative_path=? AND asset_id=?",
                        arrayOf(imported.relativePath, id)) == 1) {
                        "附件真实媒体类型更新失败"
                    }
                    db.update("local_assets", ContentValues().apply {
                        put("media_type", imported.mediaType)
                    }, "asset_id=? AND relative_path=?",
                        arrayOf(id, imported.relativePath))
                }
                SqliteAssetEventIndex.promotePendingReferences(db, id)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            guard.unlock()
        }
    }

    /**
     * Explicit receipt for a DURABLY committed message. A session must already have
     * an event cursor, and an asset must have been registered. Duplicate receipts
     * are idempotent. This API makes no claim to cover historical or model output.
     */
    fun recordReference(sessionId: String, messageId: String, assetId: String, kind: String) {
        require(SESSION_ID.matches(sessionId) && MESSAGE_ID.matches(messageId) &&
            SHA256.matches(assetId) && REFERENCE_KIND.matches(kind)) { "图片引用身份不合法" }
        val guard = SqliteSessionConsistencyGate.acquireMutation()
        try {
            val db = events.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.insertWithOnConflict("local_asset_references", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("message_id", messageId)
                    put("asset_id", assetId)
                    put("reference_kind", kind)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                // CONFLICT_IGNORE is useful only for the composite primary key.
                // Missing asset/session FK must throw instead of fabricating liveness.
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            guard.unlock()
        }
    }

    fun asset(assetId: String): Asset? {
        require(SHA256.matches(assetId))
        return readAsset(events.readableDatabase, assetId)
    }

    /** Verified published locations for one content digest (not a GC authority). */
    fun locations(assetId: String): List<String> {
        require(SHA256.matches(assetId))
        return events.readableDatabase.rawQuery(
            "SELECT relative_path FROM local_asset_locations WHERE asset_id=? ORDER BY relative_path",
            arrayOf(assetId),
        ).use { rows ->
            buildList { while (rows.moveToNext()) add(rows.getString(0)) }
        }
    }

    fun pendingReferenceCount(assetId: String): Long {
        require(SHA256.matches(assetId))
        return events.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM local_asset_pending_references WHERE asset_id=?",
            arrayOf(assetId),
        ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else 0L }
    }

    /**
     * Replay a bounded ordered page of original, committed events. This runs under
     * the Session mutation gate, writes references and the cursor in ONE transaction,
     * and resumes after process death without replaying any external tools.
     */
    fun reconcileCommittedEvents(limit: Int = 128): Int {
        require(limit in 1..512)
        val guard = SqliteSessionConsistencyGate.acquireMutation()
        try {
            val db = events.writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val progress = db.rawQuery(
                    "SELECT last_session,last_sequence FROM local_asset_backfill_progress WHERE id=1", null,
                ).use { rows ->
                    check(rows.moveToFirst()) { "资源回填游标丢失" }
                    rows.getString(0) to rows.getLong(1)
                }
                var session = progress.first
                var sequence = progress.second
                var count = 0
                db.rawQuery(
                    "SELECT session_id,sequence,event_type,payload FROM session_events " +
                        "WHERE session_id>? OR (session_id=? AND sequence>?) " +
                        "ORDER BY session_id,sequence LIMIT ?",
                    arrayOf(session, session, sequence.toString(), limit.toString()),
                ).use { rows ->
                    while (rows.moveToNext()) {
                        session = rows.getString(0)
                        sequence = rows.getLong(1)
                        val eventType = rows.getString(2)
                        val payload = Json.parseToJsonElement(rows.getString(3)).jsonObject
                        SqliteAssetEventIndex.indexCommittedMessageAssets(
                            db, session, sequence, eventType, payload,
                        )
                        count++
                    }
                }
                if (count > 0) {
                    val values = ContentValues().apply {
                        put("last_session", session)
                        put("last_sequence", sequence)
                    }
                    check(db.update("local_asset_backfill_progress", values, "id=1", null) == 1) {
                        "资源回填游标无法持久化"
                    }
                }
                db.setTransactionSuccessful()
                return count
            } finally {
                db.endTransaction()
            }
        } finally {
            guard.unlock()
        }
    }

    internal data class PublishedBackfill(val registered: Int, val unclassified: Int)

    /**
     * Re-register previously published content-addressed files. Never publish paths
     * found via symlinks or unverified bytes; unknown legacy filenames stay untouched
     * and are reported, not silently treated as garbage.
     */
    fun reconcilePublishedFiles(workspaceRoot: File, files: List<File>): PublishedBackfill {
        val dir = File(workspaceRoot, ".dsh/attachments")
        check(!Files.isSymbolicLink(workspaceRoot.toPath()) &&
            !Files.isSymbolicLink(File(workspaceRoot, ".dsh").toPath()) &&
            !Files.isSymbolicLink(dir.toPath())) { "附件根目录存在符号链接" }
        val expectedParent = dir.canonicalFile
        var added = 0
        var unclassified = 0
        for (file in files) {
            check(file.parentFile.canonicalFile == expectedParent &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "发现不安全的历史附件文件：${file.name}"
            }
            if (file.name.startsWith(".")) continue // unpublished temporary file
            val digest = file.name.substringBefore('.')
            val suffix = file.name.substringAfter('.', "")
            if (!SHA256.matches(digest) ||
                (suffix.isNotEmpty() && !suffix.matches(Regex("[a-z0-9]{1,10}")))) {
                unclassified++
                continue
            }
            val path = ".dsh/attachments/${file.name}"
            if (locations(digest).contains(path)) continue
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    hash.update(buffer, 0, read)
                }
            }
            check(hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == digest) {
                "历史附件文件名与真实内容哈希冲突：${file.name}"
            }
            val mediaType = when (suffix) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "txt" -> "text/plain"
                "json" -> "application/json"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
            register(LocalImportedAttachment(
                name = file.name, relativePath = path, mediaType = mediaType,
                bytes = file.length(), attachmentId = digest,
            ))
            added++
        }
        return PublishedBackfill(added, unclassified)
    }

    fun referenceCount(assetId: String): Long {
        require(SHA256.matches(assetId))
        return events.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM local_asset_references WHERE asset_id=?",
            arrayOf(assetId),
        ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else 0L }
    }

    private fun readAsset(db: SQLiteDatabase, id: String): Asset? =
        db.rawQuery(
            "SELECT asset_id,relative_path,media_type,byte_size,state " +
                "FROM local_assets WHERE asset_id=?", arrayOf(id),
        ).use { rows ->
            if (rows.moveToFirst()) Asset(
                rows.getString(0), rows.getString(1), rows.getString(2),
                rows.getLong(3), rows.getString(4),
            ) else null
        }

    companion object {
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,128}")
        private val MESSAGE_ID = Regex("[A-Za-z0-9._:-]{1,160}")
        private val REFERENCE_KIND = Regex("[a-z_]{2,36}")

    }
}
