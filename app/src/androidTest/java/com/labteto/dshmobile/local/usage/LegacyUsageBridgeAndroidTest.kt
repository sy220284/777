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
}
