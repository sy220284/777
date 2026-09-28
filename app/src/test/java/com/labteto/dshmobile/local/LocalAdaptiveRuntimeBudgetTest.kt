package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAdaptiveRuntimeBudgetTest {
    @Test
    fun expandsLongComplexWorkAboveConfiguredBaseline() {
        val limit = adaptiveAgentStepLimit(
            configuredBase = 20,
            task = ("全量审计、排查、修复、测试、回归并行执行。" + "细节".repeat(900)),
            contextChars = 40_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.LOW,
            kind = LocalAgentRunKind.FOREGROUND,
        )

        assertTrue(limit > 20)
        assertTrue(limit <= 128)
    }

    @Test
    fun neverShrinksBelowUserBaselineUnderPressure() {
        val limit = adaptiveAgentStepLimit(
            configuredBase = 32,
            task = "检查当前状态",
            contextChars = 480_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.HIGH,
            kind = LocalAgentRunKind.SUBAGENT,
        )

        assertTrue(limit >= 32)
    }

    @Test
    fun shrinksModelVisibleToolOutputAsHistoryFills() {
        val base = LocalHistoryBudget(
            maxHistoryChars = 100_000,
            tailChars = 40_000,
            maxSummaryChars = 8_000,
            maxToolResultChars = 40_000,
            maxHistoryTokens = 20_000,
            tailTokens = 8_000,
            maxToolResultTokens = 10_000,
        )

        val relaxed = adaptiveToolResultBudget(base, currentHistoryChars = 0, currentHistoryTokens = 0)
        val loaded = adaptiveToolResultBudget(base, currentHistoryChars = 90_000, currentHistoryTokens = 18_000)

        assertEquals(base.maxToolResultChars, relaxed.maxToolResultChars)
        assertEquals(base.maxToolResultTokens, relaxed.maxToolResultTokens)
        assertTrue(loaded.maxToolResultChars < relaxed.maxToolResultChars)
        assertTrue(loaded.maxToolResultTokens < relaxed.maxToolResultTokens)
        assertTrue(loaded.maxToolResultChars >= 8_000)
        assertTrue(loaded.maxToolResultTokens >= 2_000)
    }
}
