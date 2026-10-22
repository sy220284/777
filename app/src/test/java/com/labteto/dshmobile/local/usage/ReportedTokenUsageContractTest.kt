package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.model.DeepSeekTokenUsage
import com.labteto.dshmobile.local.model.normalizedForAccounting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportedTokenUsageContractTest {
    @Test fun contradictoryCacheCountersStayWithinReportedInput() {
        val normalized = ReportedTokenUsage(
            inputTokens = 12, cacheReadTokens = 4,
            cacheMissTokens = 99, cacheWriteTokens = 88,
            outputTokens = -10, reasoningTokens = -5, reported = true,
        ).normalizedForAccounting()
        assertEquals(12L, normalized.inputTokens)
        assertEquals(4L, normalized.cacheReadTokens)
        assertEquals(8L, normalized.cacheMissTokens)
        assertEquals(8L, normalized.cacheWriteTokens)
        assertEquals(0L, normalized.outputTokens)
        assertEquals(0L, normalized.reasoningTokens)
        assertTrue(normalized.reported)
    }

    @Test fun missingCacheMissIsInferredOnlyAtProviderBoundary() {
        val sample = ReportedTokenUsage(inputTokens = 10, cacheReadTokens = 3, reported = true)
        assertEquals(0L, sample.normalizedForAccounting().cacheMissTokens)
        assertEquals(7L, sample.normalizedForAccounting(inferMissingCacheMiss = true).cacheMissTokens)
        assertEquals(
            7L,
            DeepSeekTokenUsage(promptTokens = 10, cacheHitTokens = 3, reported = true)
                .normalizedForAccounting().cacheMissTokens,
        )
        assertFalse(sample.normalizedForAccounting().reported.not())
    }

    @Test fun providerCompatibilityMatchesSharedNormalizer() {
        val oldUsage = DeepSeekTokenUsage(
            promptTokens = 50,
            cacheHitTokens = 40,
            cacheMissTokens = 100,
            cacheWriteTokens = 80,
            completionTokens = 25,
            reasoningTokens = 9,
            reported = true,
        ).normalizedForAccounting()
        val neutral = ReportedTokenUsage(
            inputTokens = 50,
            cacheReadTokens = 40,
            cacheMissTokens = 100,
            cacheWriteTokens = 80,
            outputTokens = 25,
            reasoningTokens = 9,
            reported = true,
        ).normalizedForAccounting(inferMissingCacheMiss = true)
        assertEquals(neutral.inputTokens, oldUsage.promptTokens)
        assertEquals(neutral.cacheReadTokens, oldUsage.cacheHitTokens)
        assertEquals(neutral.cacheMissTokens, oldUsage.cacheMissTokens)
        assertEquals(neutral.cacheWriteTokens, oldUsage.cacheWriteTokens)
        assertEquals(neutral.outputTokens, oldUsage.completionTokens)
        assertEquals(neutral.reasoningTokens, oldUsage.reasoningTokens)
    }
}
