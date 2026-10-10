package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.local.runtime.LocalAgentRunKind
import com.labteto.dshmobile.local.runtime.adaptiveAgentStepLimit
import com.labteto.dshmobile.local.runtime.adaptiveToolResultBudget
import com.labteto.dshmobile.local.runtime.nextAdaptiveAgentStepLimit
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
    fun configuredStepsAboveLegacy128RemainHonoredByInitialBudget() {
        val limit = adaptiveAgentStepLimit(
            configuredBase = 512,
            task = "继续执行当前授权任务",
            contextChars = 480_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.HIGH,
            kind = LocalAgentRunKind.FOREGROUND,
        )
        assertEquals(512, limit)
        val mid = adaptiveAgentStepLimit(
            configuredBase = 240,
            task = "处理长任务",
            contextChars = 0,
            contextBudgetChars = 200_000,
            pressure = HarnessResourcePressure.LOW,
            kind = LocalAgentRunKind.SUBAGENT,
        )
        assertTrue(mid >= 240)
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
    fun exhaustedSoftBudgetAlwaysGrowsSoLongTaskCanContinue() {
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = 128,
            configuredBase = 32,
            task = "全量审计并修复后完成回归测试",
            contextChars = 95_000,
            contextBudgetChars = 100_000,
            pressure = HarnessResourcePressure.HIGH,
            kind = LocalAgentRunKind.FOREGROUND,
        )

        assertTrue(next != null)
        assertTrue(requireNotNull(next) > 128)
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

    @Test
    fun adaptiveToolBudgetNeverGrowsAboveTinyBase() {
        val base = LocalHistoryBudget(
            maxHistoryChars = 2_000,
            tailChars = 1_000,
            maxSummaryChars = 500,
            maxToolResultChars = 1_000,
            maxHistoryTokens = 500,
            tailTokens = 250,
            maxToolResultTokens = 500,
        )

        val loaded = adaptiveToolResultBudget(
            base,
            currentHistoryChars = 2_000,
            currentHistoryTokens = 500,
        )

        assertTrue(loaded.maxToolResultChars <= base.maxToolResultChars)
        assertTrue(loaded.maxToolResultTokens <= base.maxToolResultTokens)
        assertTrue(loaded.maxToolResultChars > 0)
        assertTrue(loaded.maxToolResultTokens > 0)
    }


    @Test
    fun healthyContinuationCanGrowBeyondLegacy128StepCeiling() {
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = 128,
            configuredBase = 32,
            task = "全量审计、排查、修复、测试",
            contextChars = 20_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.LOW,
            kind = LocalAgentRunKind.SUBAGENT,
        )

        assertTrue(requireNotNull(next) > 128)
    }

    @Test
    fun exhaustedContextStillReceivesContinuationBudgetForRecovery() {
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = 128,
            configuredBase = 32,
            task = "继续执行",
            contextChars = 500_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.LOW,
            kind = LocalAgentRunKind.SUBAGENT,
        )

        assertTrue(requireNotNull(next) > 128)
    }

    @Test
    fun highPressureNearContextLimitStillContinuesWithSmallerGrowth() {
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = 128,
            configuredBase = 32,
            task = "继续执行",
            contextChars = 450_000,
            contextBudgetChars = 500_000,
            pressure = HarnessResourcePressure.HIGH,
            kind = LocalAgentRunKind.SUBAGENT,
        )

        assertTrue(requireNotNull(next) > 128)
    }

    @Test
    fun unknownContextBudgetDoesNotTurnIntoTaskStop() {
        val next = nextAdaptiveAgentStepLimit(
            currentLimit = 128,
            configuredBase = 32,
            task = "继续执行",
            contextChars = 10_000,
            contextBudgetChars = 0,
            pressure = HarnessResourcePressure.LOW,
            kind = LocalAgentRunKind.SUBAGENT,
        )

        assertTrue(requireNotNull(next) > 128)
    }

}
