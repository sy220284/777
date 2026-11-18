package com.labteto.dshmobile.local.usage

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageAggregate
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Request insertion, lifetime totals and retention are committed in the same transaction. */
internal class TokenUsageDatabase(
    context: Context, private val json: Json, name: String = "token_usage_v1.db",
) : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, model TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX record_time ON records(timestamp DESC, id DESC)")
        db.execSQL("CREATE INDEX record_model_time ON records(model, timestamp DESC, id DESC)")
        db.execSQL("CREATE TABLE metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion == 1 && newVersion == 2) {
            "不支持的用量数据库版本：$oldVersion -> $newVersion"
        }
        // SQLiteOpenHelper performs this upgrade in one transaction; the original ledger survives a failed upgrade.
        db.execSQL("ALTER TABLE records ADD COLUMN model TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE records SET model = COALESCE(json_extract(payload, '$.model'), '')")
        db.execSQL("DROP INDEX record_time")
        db.execSQL("CREATE INDEX record_time ON records(timestamp DESC, id DESC)")
        db.execSQL("CREATE INDEX record_model_time ON records(model, timestamp DESC, id DESC)")
    }

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

    /** Install only once, under the same SQLite transaction as the lifetime totals read. */
    fun adoptLegacyUsageOnce(legacy: LegacyUsageCounters): LegacyUsageCounters = transaction(writableDatabase) {
        val db = writableDatabase
        val previous = legacyBridge(db)
        val bridge = previous ?: run {
            val lastTimestamp = DatabaseUtils.longForQuery(
                db, "SELECT COALESCE(MAX(timestamp), 0) FROM records", null,
            )
            LegacyUsageBridge(
                offset = legacyUsageOffset(legacy, totals(db).tracked),
                unpricedTokens = legacy.unpricedTokens.coerceAtLeast(0L),
                updatedAt = maxOf(legacy.updatedAt, lastTimestamp),
            ).also { putMetadata(db, LEGACY_BRIDGE_KEY, json.encodeToString(LegacyUsageBridge.serializer(), it)) }
        }
        lifetimeUsageProjection(totals(db).tracked, bridge.offset, bridge.unpricedTokens, bridge.updatedAt)
    }

    fun legacyUsageProjection(): LegacyUsageCounters {
        val db = readableDatabase
        val bridge = legacyBridge(db) ?: LegacyUsageBridge()
        return lifetimeUsageProjection(totals(db).tracked, bridge.offset, bridge.unpricedTokens, bridge.updatedAt)
    }

    private fun projectedTotals(
        db: SQLiteDatabase,
        original: UsageLifetimeTotals = totals(db),
    ): UsageLifetimeTotals {
        val missing = legacyBridge(db)?.offset ?: return original
        return original.copy(
            tracked = original.tracked.withLegacyUnattributedGap(missing),
            modelHistoryPartial = original.modelHistoryPartial || missing.hasUnattributedGap(),
        )
    }

    private fun legacyBridge(db: SQLiteDatabase): LegacyUsageBridge? =
        metadata(db, LEGACY_BRIDGE_KEY)?.let {
            json.decodeFromString(LegacyUsageBridge.serializer(), it)
        }

    data class AppendResult(val inserted: Boolean, val pruned: Boolean, val totals: UsageLifetimeTotals)
    fun append(record: TokenUsageRecord, unpricedTokens: Long = 0L): AppendResult {
        val db = writableDatabase
        return transaction(db) {
            val before = totals(db)
            if (!insert(db, record)) return@transaction AppendResult(false, false, projectedTotals(db, before))
            val after = before.add(record)
            putMetadata(db, "totals", json.encodeToString(UsageLifetimeTotals.serializer(), after))
            legacyBridge(db)?.let { bridge ->
                val updated = bridge.copy(
                    unpricedTokens = saturatingUsageAdd(
                        bridge.unpricedTokens, unpricedTokens.coerceAtLeast(0L),
                    ),
                    updatedAt = maxOf(bridge.updatedAt, record.timestamp),
                )
                putMetadata(db, LEGACY_BRIDGE_KEY, json.encodeToString(LegacyUsageBridge.serializer(), updated))
            }
            AppendResult(true, prune(db), projectedTotals(db, after))
        }
    }

    fun retainedRecords(): List<TokenUsageRecord> = readableDatabase.rawQuery(
        "SELECT payload FROM records ORDER BY timestamp, rowid", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(json.decodeFromString(TokenUsageRecord.serializer(), cursor.getString(0)))
        }
    }
    @Serializable
    private data class HistoryCursor(
        val id: String,
        val timestamp: Long,
        val model: String?,
        val sinceMillis: Long?,
        val totalMatching: Int,
        val version: Int = 2,
    )

    /** An indexed keyset scan. A cursor carries the first page's count and its filter scope. */
    fun historyPage(model: String?, sinceMillis: Long?, cursor: String?, limit: Int):
        com.labteto.dshmobile.uicontract.UiUsageHistoryPage {
        require(limit in 1..100) { "单页查询数量不合法" }
        val anchor = cursor?.let {
            try {
                json.decodeFromString(HistoryCursor.serializer(), it)
            } catch (error: Exception) {
                throw IllegalArgumentException("历史记录标识无效，请重新加载", error)
            }
        }
        require(anchor == null || (anchor.version == 2 && anchor.id.isNotBlank() &&
            anchor.totalMatching >= 0 && anchor.model == model && anchor.sinceMillis == sinceMillis)) {
            "查询条件已变化，请重新加载"
        }
        val db = readableDatabase
        if (anchor != null) {
            val exists = db.rawQuery("SELECT timestamp FROM records WHERE id = ?", arrayOf(anchor.id)).use {
                it.moveToFirst() && it.getLong(0) == anchor.timestamp
            }
            require(exists) { "历史记录已变化，请从第一页重新加载" }
        }
        val filters = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (model != null) {
            filters += "model = ?"
            args += model
        }
        if (sinceMillis != null) {
            filters += "timestamp >= ?"
            args += sinceMillis.toString()
        }
        fun where() = filters.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " WHERE ").orEmpty()
        // COUNT uses the same ordered index and runs only on the first page.
        val total = anchor?.totalMatching ?: db.rawQuery(
            "SELECT COUNT(*) FROM records" + where(), args.toTypedArray(),
        ).use {
            check(it.moveToFirst())
            it.getInt(0)
        }
        if (anchor != null) {
            filters += "(timestamp, id) < (?, ?)"
            args += anchor.timestamp.toString()
            args += anchor.id
        }
        val items = ArrayList<TokenUsageRecord>(limit)
        var hasMore = false
        db.rawQuery(
            "SELECT payload FROM records" + where() + " ORDER BY timestamp DESC, id DESC LIMIT ?",
            (args + (limit + 1).toString()).toTypedArray(),
        ).use { rows ->
            while (rows.moveToNext()) {
                if (items.size == limit) {
                    hasMore = true
                    break
                }
                items += json.decodeFromString(TokenUsageRecord.serializer(), rows.getString(0))
            }
        }
        val last = items.lastOrNull()
        return com.labteto.dshmobile.uicontract.UiUsageHistoryPage(
            records = items,
            nextCursor = if (hasMore && last != null) {
                json.encodeToString(HistoryCursor.serializer(), HistoryCursor(
                    last.requestId, last.timestamp, model, sinceMillis, total,
                ))
            } else null,
            totalMatching = total,
        )
    }

    fun recordById(id: String): TokenUsageRecord? = readableDatabase.rawQuery(
        "SELECT payload FROM records WHERE id = ?", arrayOf(id),
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else json.decodeFromString(TokenUsageRecord.serializer(), cursor.getString(0))
    }
    fun enforceRetention(): Boolean = transaction(writableDatabase) { prune(writableDatabase) }
    fun lifetimeTotals(): UsageLifetimeTotals = projectedTotals(readableDatabase)

    /** Older installations kept only a cross-model lifetime total. Restore everything that is
     * still retained, and flag the pre-retention portion as unattributable rather than guessing. */
    fun backfillModelTotalsIfNeeded() {
        val db = writableDatabase
        transaction(db) {
            val current = totals(db)
            if (current.models.isNotEmpty() ||
                (current.tracked.requestCount == 0L && current.tracked.unreportedRequestCount == 0L)
            ) return@transaction
            val records = retainedRecords()
            val models = linkedMapOf<String, UsageModelLifetimeTotals>()
            records.forEach { record ->
                val key = record.model.trim().ifEmpty { "未知模型" }
                models[key] = (models[key] ?: UsageModelLifetimeTotals()).add(record)
            }
            val retainedCount = records.size.toLong()
            val lifetimeCount = current.tracked.requestCount + current.tracked.unreportedRequestCount
            putMetadata(db, "totals", json.encodeToString(
                UsageLifetimeTotals.serializer(),
                current.copy(models = models, modelHistoryPartial = retainedCount < lifetimeCount),
            ))
        }
    }

    private fun insert(db: SQLiteDatabase, record: TokenUsageRecord): Boolean {
        require(record.requestId.isNotBlank()) { "用量请求编号不能为空" }
        val payload = json.encodeToString(TokenUsageRecord.serializer(), record)
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES) { "用量记录超过容量上限" }
        if (DatabaseUtils.longForQuery(db, "SELECT count(*) FROM records WHERE id = ?", arrayOf(record.requestId)) > 0L) return false
        db.insertOrThrow("records", null, ContentValues().apply {
            put("id", record.requestId); put("timestamp", record.timestamp)
            put("model", record.model); put("payload", payload)
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
        private const val LEGACY_BRIDGE_KEY = "legacy_usage_bridge_v1"
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
    val models: Map<String, UsageModelLifetimeTotals> = emptyMap(),
    val modelHistoryPartial: Boolean = false,
) {
    fun add(record: TokenUsageRecord): UsageLifetimeTotals = copy(
        since = if (since == 0L) record.timestamp else minOf(since, record.timestamp),
        tracked = tracked.add(record),
        chat = if (record.context.mode == LocalUsageMode.CHAT) chat.add(record) else chat,
        work = if (record.context.mode == LocalUsageMode.WORK) work.add(record) else work,
        models = models + (record.model.trim().ifEmpty { "未知模型" } to
            (models[record.model.trim().ifEmpty { "未知模型" }] ?: UsageModelLifetimeTotals()).add(record)),
    )
}

@Serializable
internal data class UsageModelLifetimeTotals(
    val tracked: TokenUsageAggregate = TokenUsageAggregate(),
    val chat: TokenUsageAggregate = TokenUsageAggregate(),
    val work: TokenUsageAggregate = TokenUsageAggregate(),
) {
    fun add(record: TokenUsageRecord): UsageModelLifetimeTotals = copy(
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
