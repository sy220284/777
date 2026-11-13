package com.labteto.dshmobile.local

import java.math.BigInteger
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TokenUsageAccumulatorTest {
    @Test fun incrementalAccountingHandlesExtremeValuesAndUnorderedRepairs() {
        val accumulator = TokenUsageAccumulator(ZoneId.of("UTC"))
        val context = TokenUsageContext(LocalUsageMode.CHAT, turnId = "turn", action = TokenUsageAction.CHAT_REPLY)
        accumulator.add(TokenUsageRecord("final", 30L, "model", context, inputTokens = 10L, outputTokens = 7L, reported = true))
        accumulator.add(TokenUsageRecord("repair", 20L, "model", context.copy(action = TokenUsageAction.CHAT_REPAIR), inputTokens = 20L, outputTokens = 5L, reported = true))
        val snapshot = accumulator.snapshot()
        assertEquals(42L, snapshot.tracked.totalTokens)
        assertEquals(30L, snapshot.chat.averageInputPerTurn)
        assertEquals(7L, snapshot.chat.averageOutputPerTurn)
        assertEquals(25L, snapshot.chat.averageBackgroundPerTurn)
        // Snapshotting does not consume the projection or change subsequent appends.
        assertEquals(snapshot, accumulator.snapshot())
        accumulator.add(TokenUsageRecord("extreme", 10L, "model", inputTokens = Long.MAX_VALUE, outputTokens = -8L, estimatedCostCny = Double.NaN))
        assertEquals(Long.MAX_VALUE, accumulator.snapshot().tracked.totalTokens)
        assertTrue(accumulator.snapshot().tracked.estimatedCostCny.isFinite())
    }
    @Test fun tenThousandUpdatesKeepApiTotalsExactAcrossModesAndUnorderedTimestamps() {
        val accumulator = TokenUsageAccumulator(ZoneId.of("UTC"))
        var expected = BigInteger.ZERO
        repeat(10_000) { index ->
            val input = index.toLong() * 13L
            val output = index.toLong() * 7L
            expected += BigInteger.valueOf(input + output)
            accumulator.add(TokenUsageRecord(
                "r$index", (10_000 - index).toLong(), "model",
                TokenUsageContext(mode = if (index % 2 == 0) LocalUsageMode.CHAT else LocalUsageMode.WORK),
                inputTokens = input, outputTokens = output, reported = true,
            ))
        }
        val result = accumulator.snapshot()
        assertEquals(expected.longValueExact(), result.tracked.totalTokens)
        assertEquals(result.tracked.totalTokens, result.chat.aggregate.totalTokens + result.work.aggregate.totalTokens)
        assertEquals(10_000L, result.tracked.requestCount)
        assertEquals(200, result.recentRecords.size)
    }
}
