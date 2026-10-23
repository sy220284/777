package com.labteto.dshmobile.local.session

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
) : SQLiteOpenHelper(context, name, null, SCHEMA_VERSION), LocalSessionSnapshotStore {
    init {
        setWriteAheadLoggingEnabled(true)
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("不支持的 SQLite 会话快照数据库版本：$oldVersion -> $newVersion")
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
            check(db.insertWithOnConflict(
                "session_snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE,
            ) >= 0L) { "SQLite 会话保存失败：$id" }
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
        const val SCHEMA_VERSION = 1
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
