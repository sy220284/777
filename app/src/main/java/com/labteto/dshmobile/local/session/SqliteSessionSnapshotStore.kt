package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.harness.session.FutureSessionVersionException
import com.labteto.dshmobile.harness.session.SessionDocument
import com.labteto.dshmobile.harness.session.SessionLoadResult
import com.labteto.dshmobile.harness.session.SessionMigrationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Native SQLite session snapshot store.
 *
 * No legacy JSON import occurs here: existing file snapshots are never silently mixed
 * into the SQLite authority. Session events and exports will be moved in separate steps.
 *
 * Every accepted write has a monotonically increasing revision, including multiple
 * writes within one millisecond. Summary projections key against that revision.
 */
internal class SqliteSessionSnapshotStore(
    context: Context,
    private val json: Json,
    name: String = DATABASE_NAME,
    private val domainCodecs: List<LocalSessionDomainCodec> = emptyList(),
) : SQLiteOpenHelper(context, name, null, SCHEMA_VERSION), LocalSessionSnapshotStore {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE session_snapshots (" +
                "id TEXT PRIMARY KEY NOT NULL, " +
                "format_version INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "revision INTEGER NOT NULL, " +
                "payload TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX session_snapshots_recent ON session_snapshots(updated_at DESC, id)")
        db.execSQL(
            "CREATE TABLE session_summaries (" +
                "id TEXT PRIMARY KEY NOT NULL REFERENCES session_snapshots(id) ON DELETE CASCADE, " +
                "title TEXT NOT NULL, updated_at INTEGER NOT NULL, usage_mode TEXT NOT NULL, " +
                "chat_mode TEXT, group_member_count INTEGER NOT NULL, persona_id TEXT, " +
                "gallery_id TEXT, blank INTEGER NOT NULL, summary_preview TEXT, " +
                "project_id TEXT, lineage_id TEXT)"
        )
        db.execSQL("CREATE INDEX session_summaries_recent ON session_summaries(updated_at DESC, id)")
        db.execSQL(
            "CREATE TABLE session_message_tail (" +
                "session_id TEXT NOT NULL REFERENCES session_snapshots(id) ON DELETE CASCADE, " +
                "ordinal INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "PRIMARY KEY(session_id, ordinal))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) {
            "不支持的 SQLite 会话快照数据库版本：$oldVersion -> $newVersion"
        }
        // Earlier unmerged draft builds may already have the snapshot and summary
        // tables; only the derived foreground preview is added here.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS session_message_tail (" +
                "session_id TEXT NOT NULL REFERENCES session_snapshots(id) ON DELETE CASCADE, " +
                "ordinal INTEGER NOT NULL, payload TEXT NOT NULL, " +
                "PRIMARY KEY(session_id, ordinal))"
        )
    }

    override fun read(id: String): SessionLoadResult? {
        validateId(id)
        val db = readableDatabase
        return db.rawQuery(
            "SELECT format_version, updated_at, payload FROM session_snapshots WHERE id=?",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val version = cursor.getInt(0)
            val supported = SessionMigrationRegistry.CURRENT_FORMAT_VERSION
            if (version > supported) throw FutureSessionVersionException(version, supported)
            check(version == supported) { "SQLite 会话快照格式不受支持：$version" }
            val updatedAt = cursor.getLong(1)
            val payload = json.parseToJsonElement(cursor.getString(2)).jsonObject
            check(payload["id"]?.jsonPrimitive?.contentOrNull == id) {
                "SQLite 会话快照身份不一致：$id"
            }
            SessionLoadResult(
                document = SessionDocument(version, id, updatedAt, payload),
                migrated = false,
                legacy = false,
            )
        }
    }

    override fun write(id: String, payload: JsonObject, updatedAt: Long): SessionDocument {
        validateId(id)
        check(payload["id"]?.jsonPrimitive?.contentOrNull == id) {
            "SQLite 会话快照身份不一致：$id"
        }
        val snapshot = json.decodeFromJsonElement(LocalHarnessSession.serializer(), payload)
        val summary = projectLocalSessionSummary(snapshot, domainCodecs)
        val preview = snapshot.transcriptWindow.ifEmpty { snapshot.messages }
            .takeLast(MAX_PREVIEW_MESSAGES)
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val lastRevision = db.rawQuery(
                "SELECT revision FROM session_snapshots WHERE id=?",
                arrayOf(id),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            check(lastRevision < Long.MAX_VALUE) { "SQLite 会话版本号耗尽" }
            val version = SessionMigrationRegistry.CURRENT_FORMAT_VERSION
            val values = ContentValues().apply {
                put("id", id)
                put("format_version", version)
                put("updated_at", updatedAt)
                put("revision", lastRevision + 1L)
                put("payload", payload.toString())
            }
            // REPLACE deletes the old parent row, cascading through its child tables.
            // An in-place UPDATE preserves the FK graph and avoids churn on every
            // streaming/snapshot write; INSERT is only used for a genuinely new id.
            val updated = db.update("session_snapshots", values, "id=?", arrayOf(id))
            if (updated == 0) {
                db.insertOrThrow("session_snapshots", null, values)
            }
            val summaryValues = ContentValues().apply {
                put("id", summary.id)
                put("title", summary.title)
                // The persisted snapshot timestamp is authoritative for recency sorting.
                put("updated_at", updatedAt)
                put("usage_mode", summary.usageMode.name)
                put("chat_mode", summary.chatMode)
                put("group_member_count", summary.groupMemberCount)
                put("persona_id", summary.personaId)
                put("gallery_id", summary.galleryId)
                put("blank", if (summary.blank) 1 else 0)
                put("summary_preview", summary.summaryPreview)
                put("project_id", summary.projectId)
                put("lineage_id", summary.lineageId)
            }
            check(db.insertWithOnConflict(
                "session_summaries", null, summaryValues, SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L) { "SQLite 会话摘要保存失败：$id" }
            db.delete("session_message_tail", "session_id=?", arrayOf(id))
            preview.forEachIndexed { ordinal, message ->
                check(db.insertOrThrow("session_message_tail", null, ContentValues().apply {
                    put("session_id", id)
                    put("ordinal", ordinal)
                    put("payload", json.encodeToString(LocalHarnessMessage.serializer(), message))
                }) >= 0L) { "SQLite 最近消息索引写入失败：$id" }
            }
            db.setTransactionSuccessful()
            return SessionDocument(version, id, updatedAt, payload)
        } finally {
            db.endTransaction()
        }
    }

    override fun delete(id: String): Boolean {
        validateId(id)
        return writableDatabase.delete("session_snapshots", "id=?", arrayOf(id)) > 0
    }

    override val nativeSummaries: Boolean get() = true

    override fun readSummaries(): List<LocalSessionSummary> = readableDatabase.rawQuery(
        "SELECT id,title,updated_at,usage_mode,chat_mode,group_member_count," +
            "persona_id,gallery_id,blank,summary_preview,project_id,lineage_id " +
            "FROM session_summaries ORDER BY updated_at DESC,id", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(LocalSessionSummary(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    updatedAt = cursor.getLong(2),
                    usageMode = LocalUsageMode.valueOf(cursor.getString(3)),
                    chatMode = cursor.getString(4),
                    groupMemberCount = cursor.getInt(5),
                    personaId = cursor.getString(6),
                    galleryId = cursor.getString(7),
                    blank = cursor.getInt(8) != 0,
                    summaryPreview = cursor.getString(9),
                    projectId = cursor.getString(10),
                    lineageId = cursor.getString(11),
                ))
            }
        }
    }

    override fun recentMessages(id: String, limit: Int): List<LocalHarnessMessage> {
        validateId(id)
        require(limit in 1..MAX_PREVIEW_MESSAGES) { "消息预览数量越界" }
        return readableDatabase.rawQuery(
            "SELECT payload FROM session_message_tail WHERE session_id=? " +
                "ORDER BY ordinal DESC LIMIT ?",
            arrayOf(id, limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(json.decodeFromString(LocalHarnessMessage.serializer(), cursor.getString(0)))
                }
            }.asReversed()
        }
    }

    override fun ids(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM session_snapshots ORDER BY updated_at DESC, id", null,
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    override fun lastModified(id: String): Long? {
        validateId(id)
        return readableDatabase.rawQuery(
            "SELECT revision FROM session_snapshots WHERE id=?", arrayOf(id),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    override fun hasRecoverableSnapshot(id: String): Boolean = read(id) != null

    private fun validateId(id: String) {
        require(ID_PATTERN.matches(id)) { "非法会话编号：$id" }
    }

    private companion object {
        const val DATABASE_NAME = "local_session_snapshots_v1.db"
        const val SCHEMA_VERSION = 2
        const val MAX_PREVIEW_MESSAGES = 12
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
