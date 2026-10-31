package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.labteto.dshmobile.harness.session.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Derived block-level n-gram index over authoritative Session Events.
 *
 * One posting per distinct two-char pair per 64-event block avoids copying
 * historical JSON and reduces the write amplification of per-event postings.
 * Index collisions / cross-event grams can only produce false candidates;
 * the caller always confirms exact ignore-case substring semantics.
 */
internal class SqliteSessionSearchBlockIndex(private val json: Json) {
    fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS session_search_blocks (" +
                "session_id TEXT NOT NULL REFERENCES session_event_cursors(session_id) ON DELETE CASCADE," +
                "gram INTEGER NOT NULL, block INTEGER NOT NULL," +
                "PRIMARY KEY(session_id,gram,block)) WITHOUT ROWID",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS session_search_coverage (" +
                "session_id TEXT PRIMARY KEY REFERENCES session_event_cursors(session_id) ON DELETE CASCADE," +
                "through_sequence INTEGER NOT NULL)",
        )
    }

    /** Invoked within the very same transaction that commits the events. */
    fun appended(db: SQLiteDatabase, id: String, first: Long, events: List<SessionEvent>) {
        events.forEach { index(db, id, it) }
        db.insertWithOnConflict("session_search_coverage", null,
            ContentValues().apply {
                put("session_id", id)
                put("through_sequence", -1L)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        db.execSQL(
            "UPDATE session_search_coverage SET through_sequence=? " +
                "WHERE session_id=? AND through_sequence=?",
            arrayOf(events.last().sequence, id, first - 1L),
        )
    }

    /**
     * Older v4 archives carry only EventLog rows. Backfill once upon first search.
     * A transaction commits postings and coverage together, so a terminated
     * process never claims an incomplete index.
     */
    fun ensureIndexed(db: SQLiteDatabase, id: String) {
        val last = db.rawQuery(
            "SELECT next_sequence-1 FROM session_event_cursors WHERE session_id=?", arrayOf(id),
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        val covered = db.rawQuery(
            "SELECT through_sequence FROM session_search_coverage WHERE session_id=?", arrayOf(id),
        ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (covered >= last) return
        db.beginTransactionNonExclusive()
        try {
            val from = db.rawQuery(
                "SELECT through_sequence FROM session_search_coverage WHERE session_id=?", arrayOf(id),
            ).use { if (it.moveToFirst()) it.getLong(0) else -1L }
            var through = from
            db.rawQuery(
                "SELECT sequence,event_type,created_at,schema_version,payload " +
                    "FROM session_events WHERE session_id=? AND sequence>? ORDER BY sequence ASC",
                arrayOf(id, from.toString()),
            ).use { rows ->
                while (rows.moveToNext()) {
                    check(rows.getInt(3) == 1) { "索引重建遇到不支持的事件格式" }
                    val event = SessionEvent(
                        sequence = rows.getLong(0),
                        type = rows.getString(1),
                        createdAt = rows.getLong(2),
                        data = json.parseToJsonElement(rows.getString(4)).jsonObject,
                        schemaVersion = rows.getInt(3),
                    )
                    index(db, id, event)
                    through = event.sequence
                }
            }
            db.insertWithOnConflict(
                "session_search_coverage", null,
                ContentValues().apply {
                    put("session_id", id)
                    put("through_sequence", through)
                }, SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Null means a one-character query must use the original full scan. */
    fun candidates(db: SQLiteDatabase, id: String, query: String, after: Long): Cursor? {
        if (query.length < 2) return null
        val keys = grams(query).take(MAX_FILTER_GRAMS)
        if (keys.isEmpty()) return null
        val placeholders = List(keys.size) { "?" }.joinToString(",")
        return db.rawQuery(
            "SELECT e.sequence,e.event_type,e.created_at,e.schema_version,e.payload " +
                "FROM (SELECT block FROM session_search_blocks " +
                "WHERE session_id=? AND gram IN ($placeholders) " +
                "GROUP BY block HAVING COUNT(*)=?) AS b " +
                "JOIN session_events AS e ON e.session_id=? " +
                "AND e.sequence BETWEEN b.block*$BLOCK_SIZE AND b.block*$BLOCK_SIZE+63 " +
                "WHERE e.sequence>? ORDER BY e.sequence ASC",
            arrayOf(id, *keys.map { it.toString() }.toTypedArray(),
                keys.size.toString(), id, after.toString()),
        )
    }

    /** Regenerate a damaged derivative without modifying any original events. */
    fun rebuild(db: SQLiteDatabase, id: String) {
        db.beginTransactionNonExclusive()
        try {
            db.delete("session_search_blocks", "session_id=?", arrayOf(id))
            db.delete("session_search_coverage", "session_id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        ensureIndexed(db, id)
    }

    private fun index(db: SQLiteDatabase, id: String, event: SessionEvent) {
        val content = json.encodeToString(SessionEvent.serializer(), event)
        val block = event.sequence / BLOCK_SIZE
        for (gram in grams(content)) {
            db.insertWithOnConflict("session_search_blocks", null,
                ContentValues().apply {
                    put("session_id", id)
                    put("gram", gram)
                    put("block", block)
                }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    private fun grams(s: String): List<Int> {
        val hashes = LinkedHashSet<Int>()
        for (i in 0 until s.length - 1) {
            // Same two-stage Char fold used by Kotlin ignoreCase comparisons.
            val a = s[i].uppercaseChar().lowercaseChar().code
            val b = s[i + 1].uppercaseChar().lowercaseChar().code
            hashes.add(a * 65599 + b)
        }
        return hashes.toList()
    }

    private companion object {
        const val BLOCK_SIZE = 64L
        const val MAX_FILTER_GRAMS = 8
    }
}
