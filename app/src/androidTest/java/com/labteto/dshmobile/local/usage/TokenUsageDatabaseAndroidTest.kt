package com.labteto.dshmobile.local.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.TokenUsageRecord
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenUsageDatabaseAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "usage-test-${UUID.randomUUID()}.db"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directory = File(context.cacheDir, name).apply { mkdirs() }
    private var database = TokenUsageDatabase(context, json, name)
    private val legacy = LocalSessionEventLog(File(directory, "legacy.jsonl"), json)
    @After fun cleanup() { database.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    private fun record(id: String, time: Long = System.currentTimeMillis()) = TokenUsageRecord(
        id, time, "test", TokenUsageContext(mode = LocalUsageMode.CHAT),
        inputTokens = 2L, outputTokens = 3L, reported = true,
    )
    @Test fun modelLifetimeTotalsStaySeparatedAndSurviveDatabaseReopen() {
        database.migrate(legacy)
        database.append(record("m3-chat").copy(model = "MiniMax-M3"))
        database.append(record("m3-work").copy(
            model = "MiniMax-M3",
            context = TokenUsageContext(mode = LocalUsageMode.WORK),
        ))
        database.append(record("deepseek").copy(model = "deepseek-chat"))
        assertEquals(10L, database.lifetimeTotals().models.getValue("MiniMax-M3").tracked.totalTokens)
        assertEquals(5L, database.lifetimeTotals().models.getValue("MiniMax-M3").chat.totalTokens)
        assertEquals(5L, database.lifetimeTotals().models.getValue("MiniMax-M3").work.totalTokens)
        assertEquals(5L, database.lifetimeTotals().models.getValue("deepseek-chat").tracked.totalTokens)
        database.close()
        database = TokenUsageDatabase(context, json, name)
        database.backfillModelTotalsIfNeeded()
        assertEquals(10L, database.lifetimeTotals().models.getValue("MiniMax-M3").tracked.totalTokens)
        assertFalse(database.lifetimeTotals().modelHistoryPartial)
    }

    @Test fun cacheWriteTokensPersistAndAggregateWithoutInflatingTotalTokens() {
        database.migrate(legacy)
        val value = record("cache-write").copy(
            inputTokens = 100L,
            cacheHitTokens = 60L,
            cacheMissTokens = 40L,
            cacheWriteTokens = 15L,
            outputTokens = 20L,
        )

        assertTrue(database.append(value).inserted)
        assertEquals(15L, database.recordById("cache-write")?.cacheWriteTokens)
        val aggregate = database.lifetimeTotals().tracked
        assertEquals(15L, aggregate.cacheWriteTokens)
        assertEquals(120L, aggregate.totalTokens)
    }

    @Test fun minimaxDeepseekMinimaxUsageSurvivesReopenWithoutReplayOrCrossModelMixing() {
        database.migrate(legacy)
        val first = record("m3-first").copy(
            model = "MiniMax-M3", inputTokens = 10L, cacheHitTokens = 2L,
            cacheMissTokens = 8L, cacheWriteTokens = 3L,
            outputTokens = 7L, reasoningTokens = 4L,
        )
        val other = record("ds-middle").copy(
            model = "deepseek-chat", inputTokens = 40L, cacheHitTokens = 10L,
            cacheMissTokens = 30L, outputTokens = 10L,
        )
        val last = record("m3-last").copy(
            model = "MiniMax-M3", inputTokens = 20L, cacheHitTokens = 7L,
            cacheMissTokens = 13L, cacheWriteTokens = 5L,
            outputTokens = 3L, reasoningTokens = 2L,
        )
        assertTrue(database.append(first).inserted)
        assertTrue(database.append(other).inserted)
        assertTrue(database.append(last).inserted)
        assertFalse(database.append(first.copy(inputTokens = 99_999L)).inserted)

        val before = database.lifetimeTotals()
        assertEquals(90L, before.tracked.totalTokens)
        assertEquals(3L, before.tracked.requestCount)
        assertEquals(8L, before.tracked.cacheWriteTokens)
        assertEquals(6L, before.tracked.reasoningTokens)
        assertEquals(40L, before.models.getValue("MiniMax-M3").tracked.totalTokens)
        assertEquals(50L, before.models.getValue("deepseek-chat").tracked.totalTokens)

        database.close()
        database = TokenUsageDatabase(context, json, name)
        database.backfillModelTotalsIfNeeded()
        val after = database.lifetimeTotals()
        assertEquals(before.tracked, after.tracked)
        assertEquals(before.models, after.models)
        assertEquals(3L, database.recordById("m3-first")?.cacheWriteTokens)
        assertEquals(4L, database.recordById("m3-first")?.reasoningTokens)
        assertEquals("deepseek-chat", database.recordById("ds-middle")?.model)
        assertFalse(database.append(last).inserted)
        assertEquals(3L, database.lifetimeTotals().tracked.requestCount)
    }

    @Test fun failedTotalsWriteRollsBackRequestAndAllowsSameIdRetry() {
        database.migrate(legacy)
        database.writableDatabase.execSQL("CREATE TRIGGER fail_totals BEFORE INSERT ON metadata WHEN NEW.id = 'totals' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { database.append(record("retry")) }.isFailure)
        assertNull(database.recordById("retry"))
        assertEquals(0L, database.lifetimeTotals().tracked.totalTokens)
        database.writableDatabase.execSQL("DROP TRIGGER fail_totals")
        assertTrue(database.append(record("retry")).inserted)
        assertFalse(database.append(record("retry")).inserted)
        assertEquals(5L, database.lifetimeTotals().tracked.totalTokens)
        database.close()
        database = TokenUsageDatabase(context, json, name)
        assertFalse(database.append(record("retry")).inserted)
        assertEquals(1L, database.lifetimeTotals().tracked.requestCount)
    }
    @Test fun oversizedRecordDoesNotReserveIdentityOrChangeTotals() {
        database.migrate(legacy)
        assertTrue(runCatching { database.append(record("large").copy(model = "x".repeat(20_000))) }.isFailure)
        assertTrue(database.append(record("large")).inserted)
        assertEquals(1L, database.lifetimeTotals().tracked.requestCount)
    }
    @Test fun migrationPreservesLifetimeTotalsAndBoundsDetailsForUnorderedHistory() {
        val now = System.currentTimeMillis()
        for (index in 0..10_000) {
            val value = record("r$index", now - (index % 10) * 1000L)
            legacy.append("usage/request", json.encodeToJsonElement(TokenUsageRecord.serializer(), value).jsonObject)
        }
        // A duplicate and an expired record must not inflate the retained request count.
        legacy.append("usage/request", json.encodeToJsonElement(TokenUsageRecord.serializer(), record("r5", now)).jsonObject)
        legacy.append("usage/request", json.encodeToJsonElement(TokenUsageRecord.serializer(), record("expired", now - TokenUsageDatabase.RETENTION_MILLIS - 1L)).jsonObject)
        database.migrate(legacy)
        assertEquals(9_000, database.retainedRecords().size)
        assertNull(database.recordById("expired"))
        assertEquals(10_002L, database.lifetimeTotals().tracked.requestCount)
        assertEquals(50_010L, database.lifetimeTotals().chat.totalTokens)
        assertTrue(legacy.snapshot().isEmpty())
        database.migrate(legacy)
        assertEquals(10_002L, database.lifetimeTotals().tracked.requestCount)
    }
    @Test fun failedMigrationLeavesLegacyUntouchedAndCanBeRetried() {
        legacy.append("usage/request", json.encodeToJsonElement(TokenUsageRecord.serializer(), record("legacy")).jsonObject)
        database.writableDatabase.execSQL("CREATE TRIGGER fail_migration BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { database.migrate(legacy) }.isFailure)
        assertEquals(1, legacy.snapshot().size)
        assertTrue(database.retainedRecords().isEmpty())
        database.writableDatabase.execSQL("DROP TRIGGER fail_migration")
        database.migrate(legacy)
        assertEquals(5L, database.lifetimeTotals().tracked.totalTokens)
    }
    @Test fun malformedLegacyPayloadDoesNotBlockNewAccounting() {
        legacy.append("usage/request", kotlinx.serialization.json.buildJsonObject {
            put("requestId", kotlinx.serialization.json.JsonPrimitive("bad"))
            put("inputTokens", kotlinx.serialization.json.JsonPrimitive("not a number"))
        })
        legacy.append("usage/request", json.encodeToJsonElement(TokenUsageRecord.serializer(), record("valid")).jsonObject)
        database.migrate(legacy)
        assertEquals(1L, database.lifetimeTotals().tracked.requestCount)
        database.readableDatabase.rawQuery("SELECT payload FROM metadata WHERE id = 'migration_invalid_records'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("1", it.getString(0))
        }
        assertTrue(database.append(record("new")).inserted)
    }


    @Test fun versionOneLedgerUpgradesWithExactModelValuesAndNoLostRecords() {
        database.close()
        context.deleteDatabase(name)
        val old = context.openOrCreateDatabase(name, android.content.Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE records (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, payload TEXT NOT NULL)")
        old.execSQL("CREATE INDEX record_time ON records(timestamp)")
        old.execSQL("CREATE TABLE metadata (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        val original = record("from-v1").copy(model = "MiniMax-M3")
        old.execSQL(
            "INSERT INTO records (id, timestamp, payload) VALUES (?, ?, ?)",
            arrayOf(original.requestId, original.timestamp, json.encodeToString(TokenUsageRecord.serializer(), original)),
        )
        old.setVersion(1)
        old.close()

        database = TokenUsageDatabase(context, json, name)
        val page = database.historyPage("MiniMax-M3", null, null, 20)
        assertEquals(listOf("from-v1"), page.records.map(TokenUsageRecord::requestId))
        assertEquals(1, page.totalMatching)
        assertNull(page.nextCursor)
        assertEquals(2, database.readableDatabase.version)
        assertEquals("MiniMax-M3", database.recordById("from-v1")?.model)
        database.close()
        database = TokenUsageDatabase(context, json, name)
        assertEquals(1, database.historyPage("MiniMax-M3", null, null, 20).totalMatching)
    }

    @Test fun indexedPaginationAtTenThousandRecordsUsesOneStableCount() {
        val db = database.writableDatabase
        val now = System.currentTimeMillis()
        val total = 9_900
        val matches = 6_600
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                "INSERT INTO records (id, timestamp, model, payload) VALUES (?, ?, ?, ?)",
            )
            repeat(total) { n ->
                val item = record("stress-${n.toString().padStart(5, '0')}", now - (n / 4) * 1_000L)
                    .copy(model = if (n % 3 == 0) "deepseek-chat" else "MiniMax-M3")
                insert.clearBindings()
                insert.bindString(1, item.requestId)
                insert.bindLong(2, item.timestamp)
                insert.bindString(3, item.model)
                insert.bindString(4, json.encodeToString(TokenUsageRecord.serializer(), item))
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val queryPlan = db.rawQuery(
            "EXPLAIN QUERY PLAN SELECT payload FROM records WHERE model = ? AND (timestamp, id) < (?, ?) ORDER BY timestamp DESC, id DESC LIMIT 101",
            arrayOf("MiniMax-M3", now.toString(), "stress-99999"),
        ).use { rows -> buildString {
            while (rows.moveToNext()) append(rows.getString(3))
        } }
        assertTrue("Missing index: $queryPlan", queryPlan.contains("record_model_time"))
        val started = android.os.SystemClock.elapsedRealtimeNanos()
        val seen = HashSet<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = database.historyPage("MiniMax-M3", null, cursor, 100)
            assertEquals(matches, page.totalMatching)
            assertTrue(page.records.size <= 100)
            page.records.forEach { assertTrue("Duplicated row: ${it.requestId}", seen.add(it.requestId)) }
            cursor = page.nextCursor
            pages++
            assertTrue("Cursor did not finish", pages <= 100)
        } while (cursor != null)
        val millis = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        android.util.Log.i("TokenUsageHistoryPerf", "rows=$total matches=$matches pages=$pages duration_ms=$millis")
        assertEquals(matches, seen.size)
        assertEquals(66, pages)
        // Wide emulator tolerance; the query plan assertion enforces indexed access.
        assertTrue("Indexed paging exceeded 30s: ${millis}ms", millis < 30_000)

        val first = database.historyPage("MiniMax-M3", now - 1_000L, null, 1)
        assertEquals(5, first.totalMatching)
        assertNotNull(first.nextCursor)
        assertTrue(runCatching {
            database.historyPage("deepseek-chat", now - 1_000L, first.nextCursor, 1)
        }.isFailure)
        assertEquals(5, database.historyPage("MiniMax-M3", now - 1_000L, first.nextCursor, 1).totalMatching)
    }
}
