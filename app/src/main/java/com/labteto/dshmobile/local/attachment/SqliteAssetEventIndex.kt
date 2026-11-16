package com.labteto.dshmobile.local.attachment

import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.local.session.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.model.LOCAL_IMAGE_REF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Session Event transaction owns the asset reference projection and schema.
 * Catalog reads/writes through the Session database, while the database calls this
 * stateless index; neither implementation needs to import the other.
 */
internal object SqliteAssetEventIndex {
    private val SHA256 = Regex("[a-f0-9]{64}")

        /**
         * Called WITHIN the same SQL transaction that appends a user/assistant
         * message event. Missing legacy/generated assets leave a durable pending
         * reference that is promoted atomically once their bytes are registered.
         */
        fun indexCommittedMessageAssets(
            db: SQLiteDatabase,
            sessionId: String,
            sequence: Long,
            eventType: String,
            payload: JsonObject,
        ) {
            val kind = when (eventType) {
                "user/message" -> "user_image"
                "assistant/message" -> "assistant_image"
                "tool/result" -> "tool_image"
                LOCAL_AGENT_INBOX_EVENT_TYPE -> "inbox_image"
                "chat/active-transcript" -> "chat_image"
                else -> return
            }
            val digests = linkedSetOf<String>()
            fun visit(element: JsonElement?) {
                when (element) {
                    is JsonObject -> {
                        val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                        val blockKind = (element["kind"] as? JsonPrimitive)?.contentOrNull
                        if (type == LOCAL_IMAGE_REF || blockKind == "image") {
                            val hash = sequenceOf("attachmentId", "attachment_id")
                                .mapNotNull { key ->
                                    (element[key] as? JsonPrimitive)?.contentOrNull
                                }.firstOrNull { SHA256.matches(it) }
                            if (hash != null) digests += hash
                        }
                        element.values.forEach(::visit)
                    }
                    is JsonArray -> element.forEach(::visit)
                    else -> Unit
                }
            }
            visit(payload)
            digests.forEach { digest ->
                // An unresolved local image must remain visible to the catalog even
                // before its file metadata can be verified. Never invent a file row.
                val registered = db.rawQuery(
                    "SELECT 1 FROM local_assets WHERE asset_id=?", arrayOf(digest),
                ).use { it.moveToFirst() }
                val target = if (registered) "local_asset_references"
                    else "local_asset_pending_references"
                db.execSQL(
                    "INSERT OR IGNORE INTO $target " +
                        "(session_id,message_id,asset_id,reference_kind) VALUES (?,?,?,?)",
                    arrayOf(sessionId, "event-$sequence", digest, kind),
                )
            }
        }

        /** Same transaction as registering verified content; pending rows have session FK. */
        fun promotePendingReferences(db: SQLiteDatabase, assetId: String) {
            db.execSQL(
                "INSERT OR IGNORE INTO local_asset_references " +
                    "(session_id,message_id,asset_id,reference_kind) " +
                    "SELECT session_id,message_id,asset_id,reference_kind " +
                    "FROM local_asset_pending_references WHERE asset_id=?",
                arrayOf(assetId),
            )
            db.execSQL("DELETE FROM local_asset_pending_references WHERE asset_id=?", arrayOf(assetId))
        }

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS local_assets (" +
                    "asset_id TEXT PRIMARY KEY NOT NULL, relative_path TEXT NOT NULL, " +
                    "media_type TEXT NOT NULL, byte_size INTEGER NOT NULL CHECK(byte_size>=0), " +
                    "storage_kind TEXT NOT NULL CHECK(storage_kind='file'), " +
                    "state TEXT NOT NULL CHECK(state='present'), created_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS local_asset_references (" +
                    "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE, " +
                    "message_id TEXT NOT NULL, " +
                    "asset_id TEXT NOT NULL REFERENCES local_assets(asset_id) ON DELETE RESTRICT, " +
                    "reference_kind TEXT NOT NULL, " +
                    "PRIMARY KEY(session_id,message_id,asset_id,reference_kind))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS local_asset_references_by_asset " +
                    "ON local_asset_references(asset_id)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS local_asset_locations (" +
                    "relative_path TEXT PRIMARY KEY NOT NULL, " +
                    "asset_id TEXT NOT NULL REFERENCES local_assets(asset_id) ON DELETE RESTRICT, " +
                    "media_type TEXT NOT NULL, byte_size INTEGER NOT NULL CHECK(byte_size>=0))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS local_asset_locations_by_asset " +
                    "ON local_asset_locations(asset_id)"
            )
            // Preserve committed references even when legacy/generated media metadata is
            // missing. Session deletion cascades; a later verified file promotion is atomic.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS local_asset_pending_references (" +
                    "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE, " +
                    "message_id TEXT NOT NULL, asset_id TEXT NOT NULL, reference_kind TEXT NOT NULL, " +
                    "PRIMARY KEY(session_id,message_id,asset_id,reference_kind))"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS local_asset_pending_by_asset " +
                    "ON local_asset_pending_references(asset_id)"
            )
            // A durable, ordered cursor allows old SQLite rows to be backfilled in
            // bounded transactions without rebuilding at each process start.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS local_asset_backfill_progress (" +
                    "id INTEGER PRIMARY KEY CHECK(id=1), " +
                    "last_session TEXT NOT NULL, last_sequence INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT OR IGNORE INTO local_asset_backfill_progress " +
                    "(id,last_session,last_sequence) VALUES (1,'',-1)"
            )
            // v3 pilot data already has one canonical path in local_assets.
            // Backfill it idempotently when upgrading and on fresh creation.
            db.execSQL(
                "INSERT OR IGNORE INTO local_asset_locations " +
                    "(relative_path,asset_id,media_type,byte_size) " +
                    "SELECT relative_path,asset_id,media_type,byte_size FROM local_assets"
            )
        }
}
