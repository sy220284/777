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
) : SQLiteOpenHelper(context, name, null, 2) {
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
        createCheckpointTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) {
            "不支持的会话事件数据库版本：$oldVersion -> $newVersion"
        }
        createCheckpointTable(db)
    }

    private fun createCheckpointTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS session_projection_checkpoints (" +
                "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE, " +
                "checkpoint_name TEXT NOT NULL, version INTEGER NOT NULL, " +
                "instance TEXT NOT NULL, through_sequence INTEGER NOT NULL, " +
                "payload TEXT NOT NULL, PRIMARY KEY(session_id, checkpoint_name))"
        )
    }

    fun append(sessionId: String, type: String, data: JsonObject): SessionEvent =
        appendBatch(sessionId, listOf(type to data)).single()

    /**
     * Atomic bounded append. One SQLite WAL transaction reserves the whole contiguous
     * sequence range, then commits every event (or none). Independent readers can
     * continue reading the previous WAL snapshot while this writer commits.
     */
    fun appendBatch(
        sessionId: String,
        entries: List<Pair<String, JsonObject>>,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(entries.size in 1..MAX_BATCH) { "事件批量写入数量越界" }
        require(entries.all { (type, _) -> type.isNotBlank() && type.length <= MAX_TYPE_LENGTH }) {
            "事件类型不合法"
        }
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
            val startSequence = database.rawQuery(
                "SELECT next_sequence FROM session_event_cursors WHERE session_id=?",
                arrayOf(sessionId),
            ).use { rows ->
                check(rows.moveToFirst()) { "缺少会话事件游标：$sessionId" }
                rows.getLong(0)
            }
            check(startSequence >= 0L && startSequence <= Long.MAX_VALUE - entries.size) {
                "会话事件序号已耗尽"
            }
            val nextSequence = startSequence + entries.size
            check(database.update(
                "session_event_cursors",
                ContentValues().apply { put("next_sequence", nextSequence) },
                "session_id=?", arrayOf(sessionId),
            ) == 1) { "事件序号更新失败：$sessionId" }
            val written = entries.mapIndexed { index, (type, payload) ->
                val sequence = startSequence + index
                val createdAt = clock()
                database.insertOrThrow("session_events", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("sequence", sequence)
                    put("event_type", type)
                    put("created_at", createdAt)
                    put("schema_version", EVENT_SCHEMA_VERSION)
                    put("payload", payload.toString())
                })
                SessionEvent(sequence, type, createdAt, payload)
            }
            database.setTransactionSuccessful()
            return written
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

    /**
     * A derived checkpoint must belong to this exact durable event generation.
     * Deleting/resetting a session invalidates its old checkpoint automatically via
     * the FK and the random instance token, including delete-then-recreate races.
     */
    fun readProjectionCheckpoint(
        sessionId: String, name: String, version: Int,
    ): LocalSessionProjectionCheckpoint? {
        validateSessionId(sessionId)
        validateCheckpointName(name)
        return readableDatabase.rawQuery(
            "SELECT c.version,c.instance,c.through_sequence,c.payload " +
                "FROM session_projection_checkpoints c " +
                "JOIN session_event_cursors e ON e.session_id=c.session_id " +
                "WHERE c.session_id=? AND c.checkpoint_name=? AND c.version=? " +
                "AND c.instance=e.instance AND c.through_sequence<e.next_sequence",
            arrayOf(sessionId, name, version.toString()),
        ).use { rows ->
            if (rows.moveToFirst()) LocalSessionProjectionCheckpoint(
                version = rows.getInt(0),
                identity = rows.getString(1),
                throughSequence = rows.getLong(2),
                payload = rows.getString(3),
            ) else null
        }
    }

    fun writeProjectionCheckpoint(
        sessionId: String, name: String, checkpoint: LocalSessionProjectionCheckpoint,
    ): Boolean {
        validateSessionId(sessionId)
        validateCheckpointName(name)
        require(checkpoint.payload.toByteArray(Charsets.UTF_8).size <= MAX_CHECKPOINT_BYTES) {
            "投影检查点超过 8 MiB 上限"
        }
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            val identity = db.rawQuery(
                "SELECT instance,next_sequence FROM session_event_cursors WHERE session_id=?",
                arrayOf(sessionId),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            } ?: return false
            if (checkpoint.identity != identity.first ||
                checkpoint.throughSequence < -1L ||
                checkpoint.throughSequence >= identity.second
            ) return false
            val previousSequence = db.rawQuery(
                "SELECT version,instance,through_sequence FROM session_projection_checkpoints " +
                    "WHERE session_id=? AND checkpoint_name=?",
                arrayOf(sessionId, name),
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(
                    cursor.getInt(0), cursor.getString(1), cursor.getLong(2),
                ) else null
            }
            if (previousSequence != null &&
                previousSequence.first == checkpoint.version &&
                previousSequence.second == checkpoint.identity &&
                previousSequence.third > checkpoint.throughSequence
            ) return false
            val values = ContentValues().apply {
                put("session_id", sessionId)
                put("checkpoint_name", name)
                put("version", checkpoint.version)
                put("instance", checkpoint.identity)
                put("through_sequence", checkpoint.throughSequence)
                put("payload", checkpoint.payload)
            }
            val updated = db.update(
                "session_projection_checkpoints", values,
                "session_id=? AND checkpoint_name=?", arrayOf(sessionId, name),
            )
            if (updated == 0) db.insertOrThrow("session_projection_checkpoints", null, values)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun clearSession(sessionId: String) {
        validateSessionId(sessionId)
        writableDatabase.delete("session_event_cursors", "session_id=?", arrayOf(sessionId))
    }

    private fun validateCheckpointName(name: String) {
        require(CHECKPOINT_PATTERN.matches(name)) { "检查点名称不合法" }
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
        val CHECKPOINT_PATTERN = Regex("[a-z0-9.-]{1,64}")
        const val EVENT_SCHEMA_VERSION = 1
        const val MAX_TYPE_LENGTH = 256
        const val MAX_PAGE = 200
        const val MAX_BATCH = 256
        const val MAX_CHECKPOINT_BYTES = 8 * 1024 * 1024
    }
}
