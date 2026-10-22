package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.TokenUsageAggregate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyUsageProjectionTest {
    @Test fun historicalGapIsPreservedWithoutDoubleCountingExistingSqliteRecords() {
        val before = TokenUsageAggregate(
            inputTokens = 90,
            cacheHitTokens = 20,
            cacheMissTokens = 70,
            outputTokens = 15,
            requestCount = 9,
            estimatedCostCny = 1.5,
        )
        val legacy = LegacyUsageCounters(
            inputTokens = 100,
            cacheHitTokens = 21,
            cacheMissTokens = 79,
            outputTokens = 20,
            requestCount = 10,
            estimatedCostCny = 2.0,
            unpricedTokens = 8,
            updatedAt = 1000,
        )
        val gap = legacyUsageOffset(legacy, before)
        val audited = before.withLegacyUnattributedGap(gap)
        assertEquals(100L, audited.inputTokens)
        assertEquals(10L, audited.requestCount)
        assertTrue(gap.hasUnattributedGap())
        assertEquals(10L, gap.inputTokens)
        assertEquals(1L, gap.requestCount)
        val initial = lifetimeUsageProjection(before, gap, 8, 1000)
        assertEquals(100L, initial.inputTokens)
        assertEquals(10L, initial.requestCount)
        assertEquals(2.0, initial.estimatedCostCny, 0.000001)

        // A newly committed request increases the DB lifetime, but never the frozen gap.
        val after = before.copy(
            inputTokens = 110,
            outputTokens = 19,
            requestCount = 10,
            estimatedCostCny = 1.8,
        )
        val current = lifetimeUsageProjection(after, gap, 8, 1100)
        assertEquals(120L, current.inputTokens)
        assertEquals(11L, current.requestCount)
        assertEquals(2.3, current.estimatedCostCny, 0.000001)
        assertEquals(8L, current.unpricedTokens)
    }

    @Test fun sqliteAheadOfOldPrefsDoesNotRegressOrAddDuplicateHistory() {
        val sqlite = TokenUsageAggregate(
            inputTokens = 220,
            outputTokens = 75,
            requestCount = 40,
            estimatedCostCny = 7.0,
        )
        val backup = LegacyUsageCounters(
            inputTokens = 100,
            outputTokens = 30,
            requestCount = 20,
            estimatedCostCny = 5.0,
        )
        val gap = legacyUsageOffset(backup, sqlite)
        assertEquals(0L, gap.inputTokens)
        assertEquals(0L, gap.requestCount)
        assertEquals(0.0, gap.estimatedCostCny, 0.000001)
        val result = lifetimeUsageProjection(sqlite, gap, 0, 123)
        assertEquals(220L, result.inputTokens)
        assertEquals(40L, result.requestCount)
        assertTrue(result.estimatedCostCny >= backup.estimatedCostCny)
    }
}
