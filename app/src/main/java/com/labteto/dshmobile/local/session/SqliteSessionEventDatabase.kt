package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labteto.dshmobile.harness.session.SessionEvent
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * SQLite implementation of the append-only Session Event persistence primitive.
 *
 * The row and sequence cursor commit in one WAL transaction. This is intentionally
 * independent of the existing JSONL adapter until its repair, export and projection
 * consumers can all be cut over together; an incomplete dual-write is not safe.
 */
internal class SqliteSessionEventDatabase(
    context: Context,
    private val json: Json,
    name: String = "local_session_events_v1.db",
    private val clock: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE session_event_cursors (" +
                "session_id TEXT PRIMARY KEY NOT NULL, " +
                "next_sequence INTEGER NOT NULL, " +
                "instance TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE session_events (" +
                "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE, " +
                "sequence INTEGER NOT NULL, event_type TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL, schema_version INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, PRIMARY KEY(session_id, sequence))"
        )
        db.execSQL(
            "CREATE INDEX session_event_type_latest " +
                "ON session_events(session_id, event_type, sequence DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("不支持的会话事件数据库版本：$oldVersion -> $newVersion")
    }

    fun append(sessionId: String, type: String, data: JsonObject): SessionEvent {
        validateSessionId(sessionId)
        require(type.isNotBlank() && type.length <= MAX_TYPE_LENGTH) { "事件类型不合法" }
        val createdAt = clock()
        val database = writableDatabase
        database.beginTransactionNonExclusive()
        try {
            database.insertWithOnConflict(
                "session_event_cursors", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("next_sequence", 0L)
                    put("instance", UUID.randomUUID().toString())
                }, SQLiteDatabase.CONFLICT_IGNORE,
            )
            val sequence = database.rawQuery(
                "SELECT next_sequence FROM session_event_cursors WHERE session_id=?",
                arrayOf(sessionId),
            ).use { rows ->
                check(rows.moveToFirst()) { "缺少会话事件游标：$sessionId" }
                rows.getLong(0)
            }
            check(sequence >= 0L && sequence < Long.MAX_VALUE) { "会话事件序号已耗尽" }
            check(database.update(
                "session_event_cursors",
                ContentValues().apply { put("next_sequence", sequence + 1L) },
                "session_id=?", arrayOf(sessionId),
            ) == 1) { "事件序号更新失败：$sessionId" }
            database.insertOrThrow("session_events", null, ContentValues().apply {
                put("session_id", sessionId)
                put("sequence", sequence)
                put("event_type", type)
                put("created_at", createdAt)
                put("schema_version", EVENT_SCHEMA_VERSION)
                put("payload", data.toString())
            })
            database.setTransactionSuccessful()
            return SessionEvent(sequence, type, createdAt, data)
        } finally {
            database.endTransaction()
        }
    }

    fun latestSequence(sessionId: String): Long =
        nextSequence(sessionId)?.minus(1L) ?: -1L

    fun instance(sessionId: String): String? {
        validateSessionId(sessionId)
        return readableDatabase.rawQuery(
            "SELECT instance FROM session_event_cursors WHERE session_id=?",
            arrayOf(sessionId),
        ).use { rows -> if (rows.moveToFirst()) rows.getString(0) else null }
    }

    private fun nextSequence(sessionId: String): Long? {
        validateSessionId(sessionId)
        return readableDatabase.rawQuery(
            "SELECT next_sequence FROM session_event_cursors WHERE session_id=?",
            arrayOf(sessionId),
        ).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null }
    }

    fun latest(
        sessionId: String,
        type: String,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
    ): SessionEvent? {
        validateSessionId(sessionId)
        return readableDatabase.rawQuery(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND event_type=? " +
                "AND sequence<? ORDER BY sequence DESC LIMIT 1",
            arrayOf(sessionId, type, beforeSequenceExclusive.toString()),
        ).use { rows -> if (rows.moveToFirst()) readEvent(rows) else null }
    }

    fun pageBeforeNewestFirst(
        sessionId: String, beforeSequenceExclusive: Long = Long.MAX_VALUE, limit: Int = 80,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(limit in 1..MAX_PAGE) { "事件分页大小不合法" }
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence<? " +
                "ORDER BY sequence DESC LIMIT ?",
            arrayOf(sessionId, beforeSequenceExclusive.toString(), limit.toString()),
        )
    }

    fun pageAfter(
        sessionId: String, sequenceExclusive: Long, limit: Int = 80,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(limit in 1..MAX_PAGE) { "事件分页大小不合法" }
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence>? " +
                "ORDER BY sequence ASC LIMIT ?",
            arrayOf(sessionId, sequenceExclusive.toString(), limit.toString()),
        )
    }

    /**
     * Stream without loading the complete event archive into a Kotlin collection.
     * The SQLite cursor is read-only and remains valid alongside one WAL writer.
     */
    fun forEachAfter(
        sessionId: String, sequenceExclusive: Long, visit: (SessionEvent) -> Unit,
    ) {
        validateSessionId(sessionId)
        readableDatabase.rawQuery(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence>? ORDER BY sequence ASC",
            arrayOf(sessionId, sequenceExclusive.toString()),
        ).use { rows ->
            while (rows.moveToNext()) visit(readEvent(rows))
        }
    }

    fun clearSession(sessionId: String) {
        validateSessionId(sessionId)
        writableDatabase.delete("session_event_cursors", "session_id=?", arrayOf(sessionId))
    }

    private fun queryPage(query: String, args: Array<String>): List<SessionEvent> =
        readableDatabase.rawQuery(query, args).use { rows ->
            buildList { while (rows.moveToNext()) add(readEvent(rows)) }
        }

    private fun readEvent(rows: android.database.Cursor): SessionEvent {
        val schemaVersion = rows.getInt(3)
        check(schemaVersion == EVENT_SCHEMA_VERSION) {
            "会话事件格式版本不受支持：$schemaVersion"
        }
        return SessionEvent(
            sequence = rows.getLong(0),
            type = rows.getString(1),
            createdAt = rows.getLong(2),
            data = json.parseToJsonElement(rows.getString(4)).jsonObject,
            schemaVersion = schemaVersion,
        )
    }

    private fun validateSessionId(value: String) {
        require(ID_PATTERN.matches(value)) { "会话编号不合法" }
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        const val EVENT_SCHEMA_VERSION = 1
        const val MAX_TYPE_LENGTH = 256
        const val MAX_PAGE = 200
    }
}
