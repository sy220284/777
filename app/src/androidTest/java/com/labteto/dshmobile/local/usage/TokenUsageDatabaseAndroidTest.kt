package com.labteto.dshmobile.local.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.TokenUsageContext
import com.labteto.dshmobile.local.TokenUsageRecord
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

}
