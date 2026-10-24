package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.local.attachment.SqliteLocalAssetCatalog
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
class SqliteSessionEventDatabase(
    context: Context,
    private val json: Json,
    name: String = "local_session_events_v1.db",
    private val clock: () -> Long = System::currentTimeMillis,
) : SQLiteOpenHelper(context, name, null, 4) {
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
                "payload TEXT NOT NULL, visible_hint INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(session_id, sequence))"
        )
        db.execSQL(
            "CREATE INDEX session_event_type_latest " +
                "ON session_events(session_id, event_type, sequence DESC)"
        )
        createCheckpointTable(db)
        createVisibleEventIndex(db)
        SqliteLocalAssetCatalog.createSchema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..3 && newVersion == 4) {
            "不支持的会话事件数据库版本：$oldVersion -> $newVersion"
        }
        if (oldVersion < 2) createCheckpointTable(db)

        // Two unmerged draft schemas used version=3: one added visible_hint,
        // the other introduced assets. Detect actual column presence instead
        // of assuming the version number uniquely identifies those drafts.
        val hasHint = db.rawQuery("PRAGMA table_info(session_events)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) if (cursor.getString(1) == "visible_hint") found = true
            found
        }
        if (!hasHint) {
            db.execSQL("ALTER TABLE session_events ADD COLUMN visible_hint INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                "UPDATE session_events SET visible_hint=1 WHERE " +
                    "event_type IN ('chat/active-transcript','work/active-transcript'," +
                    "'session/transcript-projection-baseline','session/transcript-migration-baseline') " +
                    "OR instr(payload, ?) > 0 OR " +
                    "(event_type='assistant/message' AND instr(payload, ?) > 0)",
                arrayOf("\"transcript\":", "\"replaces\":"),
            )
        }
        createVisibleEventIndex(db)
        SqliteLocalAssetCatalog.createSchema(db)
    }

    private fun createVisibleEventIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS session_events_visible_recent " +
                "ON session_events(session_id,visible_hint,sequence DESC)"
        )
    }

    private fun isVisibleTranscriptCandidate(type: String, data: JsonObject): Boolean =
        "transcript" in data ||
            (type == "assistant/message" && "replaces" in data) ||
            type == "chat/active-transcript" || type == "work/active-transcript" ||
            type == "session/transcript-projection-baseline" ||
            type == "session/transcript-migration-baseline"

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
        val mutation = SqliteSessionConsistencyGate.acquireMutation()
        try {
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
                    put("visible_hint", if (isVisibleTranscriptCandidate(type, payload)) 1 else 0)
                })
                SqliteLocalAssetCatalog.indexCommittedMessageAssets(
                    database, sessionId, sequence, type, payload,
                )
                SessionEvent(sequence, type, createdAt, payload)
            }
            database.setTransactionSuccessful()
            return written
        } finally {
            database.endTransaction()
        }
        } finally { mutation.unlock() }
    }

    /** Source generations allow derived Work reports to detect deletion/recreation and append. */
    internal fun historicalSources(): List<LocalHistoricalSessionSource> =
        readableDatabase.rawQuery(
            "SELECT session_id,instance,next_sequence FROM session_event_cursors ORDER BY session_id",
            null,
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val next = rows.getLong(2)
                    add(LocalHistoricalSessionSource(
                        id = rows.getString(0), identity = rows.getString(1),
                        size = next, modifiedAt = next,
                    ))
                }
            }
        }

    fun sessionCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM session_event_cursors", null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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

    /**
     * Indexed transcript paging skips irrelevant tool, model and Work ledger
     * rows without decoding them. Rewrite boundaries and replacement markers
     * remain visible even with empty transcript payloads.
     */
    fun pageVisibleBeforeNewestFirst(
        sessionId: String, beforeSequenceExclusive: Long = Long.MAX_VALUE, limit: Int = 80,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(limit in 1..MAX_PAGE) { "事件分页大小不合法" }
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND visible_hint=1 AND sequence<? " +
                "ORDER BY sequence DESC LIMIT ?",
            arrayOf(sessionId, beforeSequenceExclusive.toString(), limit.toString()),
        )
    }

    /** Indexed forward replay for user-visible transcript projections. */
    fun visibleEventsAfter(
        sessionId: String, sequenceExclusive: Long,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND visible_hint=1 AND sequence>? " +
                "ORDER BY sequence ASC",
            arrayOf(sessionId, sequenceExclusive.toString()),
        )
    }

    /** Most recent bounded window returned in chronological order by SQLite itself. */
    fun pageBeforeChronological(
        sessionId: String, beforeSequenceExclusive: Long = Long.MAX_VALUE, limit: Int = 80,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(limit in 1..MAX_PAGE) { "事件分页大小不合法" }
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload FROM (" +
                "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence<? " +
                "ORDER BY sequence DESC LIMIT ?) ORDER BY sequence ASC",
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

    /** Only replay event kinds interpreted by Chat/Work control reducers.
     * SQL uses (session_id,event_type,sequence) and preserves global event order.
     */
    fun selectTypesAfter(
        sessionId: String, sequenceExclusive: Long, types: Set<String>,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(types.isNotEmpty() && types.size <= 32) { "控制事件类型集合不合法" }
        val columns = List(types.size) { "?" }.joinToString(",")
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND event_type IN ($columns) " +
                "AND sequence>? ORDER BY sequence ASC",
            arrayOf(sessionId, *types.toTypedArray(), sequenceExclusive.toString()),
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
    internal fun readProjectionCheckpoint(
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

    internal fun writeProjectionCheckpoint(
        sessionId: String, name: String, checkpoint: LocalSessionProjectionCheckpoint,
    ): Boolean {
        validateSessionId(sessionId)
        validateCheckpointName(name)
        require(checkpoint.payload.toByteArray(Charsets.UTF_8).size <= MAX_CHECKPOINT_BYTES) {
            "投影检查点超过 8 MiB 上限"
        }
        val mutation = SqliteSessionConsistencyGate.acquireMutation()
        try {
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
        } finally { mutation.unlock() }
    }

    /** Cross-process writer lease for process-loss repair and semantic rewrite. */
    fun <T> withWriterLease(block: () -> T): T {
        val mutation = SqliteSessionConsistencyGate.acquireMutation()
        try {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val result = block()
                db.setTransactionSuccessful()
                return result
            } finally { db.endTransaction() }
        } finally { mutation.unlock() }
    }

    /** The cursor is valid only for the duration of the consumer callback. */
    fun <T> withEvents(sessionId: String, block: (Sequence<SessionEvent>) -> T): T {
        validateSessionId(sessionId)
        return readableDatabase.rawQuery(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? ORDER BY sequence ASC",
            arrayOf(sessionId),
        ).use { cursor ->
            block(sequence {
                while (cursor.moveToNext()) yield(readEvent(cursor))
            })
        }
    }

    fun latestMatching(
        sessionId: String,
        types: Set<String>,
        beforeSequenceExclusive: Long = Long.MAX_VALUE,
        predicate: (JsonObject) -> Boolean,
    ): SessionEvent? {
        validateSessionId(sessionId)
        require(types.isNotEmpty()) { "事件类型集合不能为空" }
        var before = beforeSequenceExclusive
        while (true) {
            val page = typedPageBefore(sessionId, types, before, MAX_PAGE)
            if (page.isEmpty()) return null
            page.firstOrNull { predicate(it.data) }?.let { return it }
            before = page.last().sequence
        }
    }

    fun <K> latestMatchingByKey(
        sessionId: String,
        types: Set<String>,
        requiredKeys: Set<K>,
        keyOf: (SessionEvent) -> K?,
    ): Map<K, SessionEvent> {
        if (requiredKeys.isEmpty()) return emptyMap()
        require(types.isNotEmpty()) { "事件类型集合不能为空" }
        val remaining = requiredKeys.toMutableSet()
        val found = linkedMapOf<K, SessionEvent>()
        var before = Long.MAX_VALUE
        while (remaining.isNotEmpty()) {
            val page = typedPageBefore(sessionId, types, before, MAX_PAGE)
            if (page.isEmpty()) break
            page.forEach { event ->
                val key = keyOf(event)
                if (key != null && remaining.remove(key)) found[key] = event
            }
            before = page.last().sequence
        }
        return found
    }

    private fun typedPageBefore(
        sessionId: String, types: Set<String>, before: Long, limit: Int,
    ): List<SessionEvent> {
        validateSessionId(sessionId)
        require(types.isNotEmpty()) { "事件类型集合不能为空" }
        val placeholders = List(types.size) { "?" }.joinToString(",")
        return queryPage(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND event_type IN ($placeholders) " +
                "AND sequence<? ORDER BY sequence DESC LIMIT ?",
            arrayOf(sessionId, *types.toTypedArray(), before.toString(), limit.toString()),
        )
    }

    fun search(sessionId: String, query: String, limit: Int, afterSequence: Long): String {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val matches = mutableListOf<String>()
        val budget = 32 * 1024
        var used = 0
        var lastSequence = afterSequence
        var more = false
        validateSessionId(sessionId)
        readableDatabase.rawQuery(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence>? ORDER BY sequence ASC",
            arrayOf(sessionId, afterSequence.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                val event = readEvent(rows)
                val encoded = json.encodeToString(SessionEvent.serializer(), event)
                if (!encoded.contains(query, ignoreCase = true)) continue
                if (matches.size >= limit.coerceIn(1, 200) || used >= budget) {
                    more = true
                    break
                }
                val line = "${event.sequence}: " + encoded.take(2048)
                if (used + line.length < budget) {
                    matches += line
                    used += line.length + 1
                    lastSequence = event.sequence
                } else {
                    more = true
                    break
                }
            }
        }
        if (matches.isEmpty()) return "未找到会话事件"
        return matches.joinToString("\n") +
            if (more) "\n[结果已分页；继续调用 session_event_search，并传 after_sequence=$lastSequence]" else ""
    }

    fun tail(sessionId: String, limit: Int): String {
        val items = pageBeforeNewestFirst(sessionId, limit = limit.coerceIn(1, 200))
        if (items.isEmpty()) return "会话事件日志为空"
        return items.asReversed().joinToString("\n") {
            json.encodeToString(SessionEvent.serializer(), it).take(2048)
        }
    }

    fun read(sessionId: String, sequence: Long, before: Int, after: Int, offsetChars: Int): String {
        val start = (sequence - before.coerceIn(0, 100)).coerceAtLeast(0L)
        val end = (sequence + after.coerceIn(0, 100)).coerceAtLeast(sequence)
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT sequence,event_type,created_at,schema_version,payload " +
                "FROM session_events WHERE session_id=? AND sequence>=? AND sequence<=? " +
                "ORDER BY sequence ASC",
            arrayOf(sessionId, start.toString(), end.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                out += json.encodeToString(SessionEvent.serializer(), readEvent(rows))
            }
        }
        if (out.isEmpty()) return "事件不存在：$sequence"
        val payload = out.joinToString("\n")
        val offset = offsetChars.coerceAtLeast(0)
        if (offset >= payload.length) return "事件读取游标超出范围：$offsetChars（总字符数 ${payload.length}）"
        val page = payload.substring(offset, minOf(payload.length, offset + 30_000))
        val next = offset + page.length
        return page + if (next < payload.length) "\n[结果已分页；继续调用 session_event_read，并传 offset_chars=$next]" else ""
    }

    fun clearSession(sessionId: String) {
        validateSessionId(sessionId)
        val mutation = SqliteSessionConsistencyGate.acquireMutation()
        try { writableDatabase.delete("session_event_cursors", "session_id=?", arrayOf(sessionId)) }
        finally { mutation.unlock() }
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
