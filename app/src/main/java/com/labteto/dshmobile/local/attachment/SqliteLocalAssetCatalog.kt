package com.labteto.dshmobile.local.attachment

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.local.session.SqliteSessionConsistencyGate
import com.labteto.dshmobile.local.session.SqliteSessionEventDatabase
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
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
 * Until old/generated assets are backfilled and all mutation paths emit reference
 * updates, this catalog must NEVER be used as the sole authority for physical GC.
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
                check(registered == Triple(id, imported.bytes, imported.mediaType)) {
                    "附件路径登记与原记录冲突"
                }
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
