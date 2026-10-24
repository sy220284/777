package com.labteto.dshmobile.local.model

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One durable row owns a receipt and its deliverable; SQLite supplies atomic commit/recovery. */
@Serializable
internal data class LocalMediaRecord(
    val id: String,
    val job: LocalMediaCreationJob? = null,
    val result: LocalMediaResult? = null,
    val video: LocalMediaVideoTask? = null,
    val deleted: Boolean = false,
) {
    val sessionId: String? get() = job?.sessionId ?: result?.sessionId ?: video?.sessionId
    val timestamp: Long get() = job?.createdAtMillis ?: result?.createdAtMillis ?: video?.startedAtMillis ?: 0
    val fileName: String? get() = result?.fileName ?: video?.fileName
}

internal class LocalMediaDatabase(context: Context, private val json: Json, name: String = "local_media_v1.db") :
    SQLiteOpenHelper(context, name, null, 1, android.database.DatabaseErrorHandler {
        throw android.database.sqlite.SQLiteDatabaseCorruptException("媒体数据库损坏，原文件保留，请恢复备份")
    }) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE media (id TEXT PRIMARY KEY, session_id TEXT, timestamp INTEGER NOT NULL, deleted INTEGER NOT NULL, pending INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX media_page ON media(deleted, timestamp DESC, id)")
        db.execSQL("CREATE INDEX media_session ON media(session_id, deleted, timestamp DESC, id)")
        db.execSQL("CREATE INDEX media_pending ON media(deleted, pending)")
        db.execSQL("CREATE TABLE metadata (id TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("不支持的媒体数据库版本：$oldVersion -> $newVersion")

    fun migrated(): Boolean = readableDatabase.rawQuery("SELECT value FROM metadata WHERE id='migrated'", null)
        .use { it.moveToFirst() }

    /** Decode all supported sources before one transaction. Failure leaves both old and new facts intact. */
    fun migrate(source: List<LocalMediaRecord>) = transaction {
        if (migrated()) return@transaction
        source.forEach(::put)
        writableDatabase.insertOrThrow("metadata", null, ContentValues().apply { put("id", "migrated"); put("value", "1") })
    }
    fun get(id: String): LocalMediaRecord? = readableDatabase.rawQuery("SELECT payload FROM media WHERE id=?", arrayOf(id))
        .use { cursor -> if (cursor.moveToFirst()) decode(cursor.getString(0)) else null }
    fun put(record: LocalMediaRecord) {
        val pending = record.job?.phase in setOf(LocalMediaCreationPhase.PREPARING, LocalMediaCreationPhase.SUBMITTING, LocalMediaCreationPhase.SAVING) ||
            record.video?.let { it.phase == LocalMediaVideoPhase.SUBMITTING ||
                it.phase == LocalMediaVideoPhase.SUCCEEDED && it.fileName == null } == true
        writableDatabase.insertWithOnConflict("media", null, ContentValues().apply {
            put("id", record.id); put("session_id", record.sessionId); put("timestamp", record.timestamp)
            put("deleted", if (record.deleted) 1 else 0); put("pending", if (pending) 1 else 0)
            put("payload", json.encodeToString(LocalMediaRecord.serializer(), record))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { if (it == -1L) throw android.database.sqlite.SQLiteException("媒体记录提交失败") }
    }
    fun remove(id: String) { writableDatabase.delete("media", "id=? AND deleted=1", arrayOf(id)) }
    fun page(sessionId: String?, limit: Int): List<LocalMediaRecord> {
        require(limit > 0)
        val filter = if (sessionId == null) "deleted=0" else "deleted=0 AND session_id=?"
        return readableDatabase.rawQuery("SELECT payload FROM media WHERE $filter ORDER BY timestamp DESC, id LIMIT ?",
            if (sessionId == null) arrayOf(limit.toString()) else arrayOf(sessionId, limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
        }
    }
    fun activeForSession(sessionId: String): List<LocalMediaRecord> =
        readableDatabase.rawQuery("SELECT payload FROM media WHERE deleted=0 AND session_id=?", arrayOf(sessionId))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) } }
    /** Call inside the deletion transaction: all sessions and media types share this namespace.
     * Fail closed if an active payload cannot be decoded; a best-effort guess can destroy media.
     */
    fun hasActiveFileReference(fileName: String): Boolean =
        readableDatabase.rawQuery("SELECT payload FROM media WHERE deleted=0", null).use { cursor ->
            while (cursor.moveToNext()) {
                if (decode(cursor.getString(0)).fileName == fileName) return@use true
            }
            false
        }
    fun pending(): List<LocalMediaRecord> = select("deleted=0 AND pending=1")
    fun deletions(): List<LocalMediaRecord> = select("deleted=1")
    private fun select(where: String): List<LocalMediaRecord> = readableDatabase.rawQuery("SELECT payload FROM media WHERE $where", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) } }
    private fun decode(payload: String): LocalMediaRecord = json.decodeFromString(LocalMediaRecord.serializer(), payload)
    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { val value = block(); db.setTransactionSuccessful(); return value }
        finally { db.endTransaction() }
    }
}
