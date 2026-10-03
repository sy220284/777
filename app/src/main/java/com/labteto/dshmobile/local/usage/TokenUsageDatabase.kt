package com.labteto.dshmobile.local.usage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Request insertion, lifetime totals and retention are committed in the same transaction. */
internal class TokenUsageDatabase(
    context: Context, private val json: Json, name: String = "token_usage_v1.db",
) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX record_time ON records(timestamp)")
        db.execSQL("CREATE TABLE metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("不支持的用量数据库版本：$oldVersion -> $newVersion")

    fun migrate(legacy: LocalSessionEventLog) {
        val db = writableDatabase
        if (metadata(db, "migrated") != null) { legacy.clear(); return }
        transaction(db) {
            var totals = totals(db)
            var invalidRecords = 0L
            legacy.withEvents { events ->
                events.filter { it.type == "usage/request" }.forEach { event ->
                    val record = runCatching {
                        json.decodeFromJsonElement(TokenUsageRecord.serializer(), event.data).boundedForStorage()
                    }.getOrElse { invalidRecords++; return@forEach }
                    if (insert(db, record)) totals = totals.add(record)
                }
            }
            putMetadata(db, "totals", json.encodeToString(UsageLifetimeTotals.serializer(), totals))
            prune(db)
            putMetadata(db, "migration_invalid_records", invalidRecords.toString())
            putMetadata(db, "migrated", "1")
        }
        // Leave the source untouched on failure. A completed migration can safely remove it.
        legacy.clear()
    }

    data class AppendResult(val inserted: Boolean, val pruned: Boolean, val totals: UsageLifetimeTotals)
    fun append(record: TokenUsageRecord): AppendResult {
        val db = writableDatabase
        return transaction(db) {
            val before = totals(db)
            if (!insert(db, record)) return@transaction AppendResult(false, false, before)
            val after = before.add(record)
            putMetadata(db, "totals", json.encodeToString(UsageLifetimeTotals.serializer(), after))
            AppendResult(true, prune(db), after)
        }
    }

    fun retainedRecords(): List<TokenUsageRecord> = readableDatabase.rawQuery(
        "SELECT payload FROM records ORDER BY timestamp, rowid", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(json.decodeFromString(TokenUsageRecord.serializer(), cursor.getString(0)))
        }
    }
    fun recordById(id: String): TokenUsageRecord? = readableDatabase.rawQuery(
        "SELECT payload FROM records WHERE id = ?", arrayOf(id),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else json.decodeFromString(TokenUsageRecord.serializer(), cursor.getString(0))
    }
    fun enforceRetention(): Boolean = transaction(writableDatabase) { prune(writableDatabase) }
    fun lifetimeTotals(): UsageLifetimeTotals = totals(readableDatabase)

    private fun insert(db: SQLiteDatabase, record: TokenUsageRecord): Boolean {
        require(record.requestId.isNotBlank()) { "用量请求编号不能为空" }
        val payload = json.encodeToString(TokenUsageRecord.serializer(), record)
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "用量记录超过容量上限" }
        if (DatabaseUtils.longForQuery(db, "SELECT count(*) FROM records WHERE id = ?", arrayOf(record.requestId)) > 0L) return false
        db.insertOrThrow("records", null, ContentValues().apply {
            put("id", record.requestId); put("timestamp", record.timestamp); put("payload", payload)
        })
        return true
    }
    private fun prune(db: SQLiteDatabase): Boolean {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        var removed = db.delete("records", "timestamp < ?", arrayOf(cutoff.toString())) > 0
        if (DatabaseUtils.queryNumEntries(db, "records") > MAX_RECORDS) {
            db.execSQL("DELETE FROM records WHERE rowid NOT IN (SELECT rowid FROM records ORDER BY timestamp DESC, rowid DESC LIMIT $RETAIN_AFTER_PRUNE)")
            removed = true
        }
        return removed
    }
    private fun totals(db: SQLiteDatabase): UsageLifetimeTotals = metadata(db, "totals")?.let {
        json.decodeFromString(UsageLifetimeTotals.serializer(), it)
    } ?: UsageLifetimeTotals()
    private fun metadata(db: SQLiteDatabase, id: String): String? = db.rawQuery(
        "SELECT payload FROM metadata WHERE id = ?", arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    private fun putMetadata(db: SQLiteDatabase, id: String, payload: String) {
        db.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("id", id); put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "保存累计用量失败" } }
    }
    private inline fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
    internal companion object {
        const val MAX_RECORDS = 10_000L
        const val RETAIN_AFTER_PRUNE = 9_000
        const val MAX_RECORD_BYTES = 4 * 1024
        const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}

@Serializable
internal data class UsageLifetimeTotals(
    val since: Long = 0L,
    val tracked: TokenUsageAggregate = TokenUsageAggregate(),
    val chat: TokenUsageAggregate = TokenUsageAggregate(),
    val work: TokenUsageAggregate = TokenUsageAggregate(),
) {
    fun add(record: TokenUsageRecord): UsageLifetimeTotals = copy(
        since = if (since == 0L) record.timestamp else minOf(since, record.timestamp),
        tracked = tracked.add(record),
        chat = if (record.context.mode == LocalUsageMode.CHAT) chat.add(record) else chat,
        work = if (record.context.mode == LocalUsageMode.WORK) work.add(record) else work,
    )
}

private fun TokenUsageAggregate.add(record: TokenUsageRecord) = copy(
    inputTokens = saturatingUsageAdd(inputTokens, record.inputTokens),
    cacheHitTokens = saturatingUsageAdd(cacheHitTokens, record.cacheHitTokens),
    cacheMissTokens = saturatingUsageAdd(cacheMissTokens, record.cacheMissTokens),
    cacheWriteTokens = saturatingUsageAdd(cacheWriteTokens, record.cacheWriteTokens),
    outputTokens = saturatingUsageAdd(outputTokens, record.outputTokens),
    reasoningTokens = saturatingUsageAdd(reasoningTokens, record.reasoningTokens),
    requestCount = saturatingUsageAdd(requestCount, if (record.reported) 1L else 0L),
    unreportedRequestCount = saturatingUsageAdd(unreportedRequestCount, if (record.reported) 0L else 1L),
    estimatedCostCny = saturatingUsageCostAdd(estimatedCostCny, record.estimatedCostCny),
)
