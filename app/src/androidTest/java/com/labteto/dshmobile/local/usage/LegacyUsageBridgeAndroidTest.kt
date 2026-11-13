package com.labteto.dshmobile.local.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labteto.dshmobile.local.TokenUsageRecord
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyUsageBridgeAndroidTest {
    @Test fun migrationSurvivesRestartAndDuplicateRequestNeverChangesLifetime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "usage-legacy-bridge-" + UUID.randomUUID() + ".db"
        val json = Json { ignoreUnknownKeys = true }
        val now = System.currentTimeMillis()
        try {
            TokenUsageDatabase(context, json, dbName).use { db ->
                val first = TokenUsageRecord(
                    requestId = "legacy-observed",
                    timestamp = now,
                    model = "deepseek-chat",
                    inputTokens = 90,
                    outputTokens = 10,
                    estimatedCostCny = 1.5,
                    reported = true,
                )
                assertTrue(db.append(first).inserted)
                val migrated = db.adoptLegacyUsageOnce(LegacyUsageCounters(
                    inputTokens = 100,
                    outputTokens = 20,
                    requestCount = 2,
                    estimatedCostCny = 2.0,
                    unpricedTokens = 9,
                    updatedAt = now,
                ))
                assertEquals(100L, migrated.inputTokens)
                assertEquals(2L, migrated.requestCount)
                assertEquals(9L, migrated.unpricedTokens)
                assertEquals(100L, db.lifetimeTotals().tracked.inputTokens)
                assertEquals(90L, db.lifetimeTotals().models.getValue("deepseek-chat").tracked.inputTokens)
                assertTrue(db.lifetimeTotals().modelHistoryPartial)
            }
            TokenUsageDatabase(context, json, dbName).use { db ->
                // Reboot with stale preference values: the one-time bridge must not be reinstalled.
                val restarted = db.adoptLegacyUsageOnce(LegacyUsageCounters(
                    inputTokens = 1000,
                    requestCount = 100,
                    unpricedTokens = 1000,
                ))
                assertEquals(100L, restarted.inputTokens)
                assertEquals(2L, restarted.requestCount)
                val newRequest = TokenUsageRecord(
                    requestId = "post-migration",
                    timestamp = now + 1,
                    model = "provider-b",
                    inputTokens = 20,
                    outputTokens = 4,
                    reported = true,
                )
                assertTrue(db.append(newRequest, unpricedTokens = 24).inserted)
                assertFalse(db.append(newRequest, unpricedTokens = 24).inserted)
                val projected = db.legacyUsageProjection()
                assertEquals(120L, projected.inputTokens)
                assertEquals(3L, projected.requestCount)
                assertEquals(33L, projected.unpricedTokens)
                assertEquals(120L, db.lifetimeTotals().tracked.inputTokens)
                assertEquals(20L, db.lifetimeTotals().models.getValue("provider-b").tracked.inputTokens)
                assertTrue(db.lifetimeTotals().modelHistoryPartial)
            }
            TokenUsageDatabase(context, json, dbName).use { db ->
                assertEquals(120L, db.legacyUsageProjection().inputTokens)
                assertEquals(3L, db.legacyUsageProjection().requestCount)
                assertEquals(33L, db.legacyUsageProjection().unpricedTokens)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }
    @Test fun conflictingOldCacheSnapshotCannotOverrideCommittedSqlitePartition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "usage-cache-partition-" + UUID.randomUUID() + ".db"
        val json = Json { ignoreUnknownKeys = true }
        try {
            TokenUsageDatabase(context, json, dbName).use { db ->
                assertTrue(db.append(TokenUsageRecord(
                    requestId = "sqlite-fact",
                    timestamp = System.currentTimeMillis(),
                    model = "provider",
                    inputTokens = 10,
                    cacheHitTokens = 5,
                    cacheMissTokens = 5,
                    cacheWriteTokens = 2,
                    reported = true,
                )).inserted)
                val migrated = db.adoptLegacyUsageOnce(LegacyUsageCounters(
                    inputTokens = 10,
                    cacheHitTokens = 10,
                    cacheMissTokens = 0,
                ))
                assertEquals(10L, migrated.inputTokens)
                assertEquals(5L, migrated.cacheHitTokens)
                assertEquals(5L, migrated.cacheMissTokens)
                assertEquals(2L, migrated.cacheWriteTokens)
            }
            TokenUsageDatabase(context, json, dbName).use { db ->
                val totals = db.lifetimeTotals().tracked
                assertEquals(10L, totals.inputTokens)
                assertEquals(5L, totals.cacheHitTokens)
                assertEquals(5L, totals.cacheMissTokens)
                assertEquals(2L, totals.cacheWriteTokens)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test fun interruptedLegacyBridgeInstallationIsAtomicAndRetryable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "usage-bridge-rollback-" + UUID.randomUUID() + ".db"
        val json = Json { ignoreUnknownKeys = true }
        try {
            TokenUsageDatabase(context, json, dbName).use { db ->
                val row = TokenUsageRecord(
                    requestId = "before-upgrade",
                    timestamp = System.currentTimeMillis(),
                    model = "provider",
                    inputTokens = 12,
                    cacheHitTokens = 4,
                    cacheMissTokens = 8,
                    reported = true,
                )
                assertTrue(db.append(row).inserted)
                db.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_bridge BEFORE INSERT ON metadata " +
                        "WHEN NEW.id = 'legacy_usage_bridge_v1' " +
                        "BEGIN SELECT RAISE(ABORT, 'injected bridge failure'); END",
                )
                val historical = LegacyUsageCounters(
                    inputTokens = 20, cacheHitTokens = 6, cacheMissTokens = 14,
                    requestCount = 2, unpricedTokens = 3,
                )
                assertTrue(runCatching { db.adoptLegacyUsageOnce(historical) }.isFailure)
                assertEquals(12L, db.lifetimeTotals().tracked.inputTokens)
                assertEquals(1L, db.lifetimeTotals().tracked.requestCount)
                db.writableDatabase.execSQL("DROP TRIGGER fail_bridge")
                val migrated = db.adoptLegacyUsageOnce(historical)
                assertEquals(20L, migrated.inputTokens)
                assertEquals(2L, migrated.requestCount)
                assertEquals(3L, migrated.unpricedTokens)
                assertEquals(20L, db.lifetimeTotals().tracked.inputTokens)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test fun bridgeWriteFailureRollsBackNewRequestLifetimeAndUnpricedCounters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "usage-bridge-append-rollback-" + UUID.randomUUID() + ".db"
        val json = Json { ignoreUnknownKeys = true }
        try {
            TokenUsageDatabase(context, json, dbName).use { db ->
                db.adoptLegacyUsageOnce(LegacyUsageCounters(
                    inputTokens = 50, requestCount = 1, unpricedTokens = 6,
                ))
                val request = TokenUsageRecord(
                    requestId = "retry-after-bridge-error",
                    timestamp = System.currentTimeMillis(),
                    model = "provider",
                    inputTokens = 10,
                    outputTokens = 2,
                    reported = true,
                )
                db.writableDatabase.execSQL(
                    "CREATE TRIGGER fail_bridge_append BEFORE INSERT ON metadata " +
                        "WHEN NEW.id = 'legacy_usage_bridge_v1' " +
                        "BEGIN SELECT RAISE(ABORT, 'injected append failure'); END",
                )
                assertTrue(runCatching { db.append(request, unpricedTokens = 12) }.isFailure)
                assertEquals(null, db.recordById(request.requestId))
                assertEquals(50L, db.lifetimeTotals().tracked.inputTokens)
                assertEquals(1L, db.lifetimeTotals().tracked.requestCount)
                assertEquals(6L, db.legacyUsageProjection().unpricedTokens)
                db.writableDatabase.execSQL("DROP TRIGGER fail_bridge_append")
                assertTrue(db.append(request, unpricedTokens = 12).inserted)
                assertFalse(db.append(request, unpricedTokens = 12).inserted)
                assertEquals(60L, db.lifetimeTotals().tracked.inputTokens)
                assertEquals(2L, db.lifetimeTotals().tracked.requestCount)
                assertEquals(18L, db.legacyUsageProjection().unpricedTokens)
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

}
