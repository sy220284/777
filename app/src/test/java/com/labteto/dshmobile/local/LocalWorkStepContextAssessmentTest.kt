package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalPromptPressure
import com.labteto.dshmobile.local.work.LocalWorkStepContextStatus
import com.labteto.dshmobile.local.work.assessWorkStepContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkStepContextAssessmentTest {
    @Test
    fun smallRequestStaysHealthyEvenWhenHistoryRatioIsHigh() {
        val current = pressure(
            total = 18_000,
            history = 14_000,
            tools = 1_000,
            currentUser = 2_000,
        )

        val assessment = assessWorkStepContext(
            current = current,
            previous = null,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )

        assertEquals(LocalWorkStepContextStatus.HEALTHY, assessment.status)
        assertFalse(assessment.recommendsCompaction)
        assertEquals(36_000, assessment.effectiveProjectionTriggerTokens)
    }

    @Test
    fun rapidlyGrowingHistoryCanCompactBeforeAbsoluteTrigger() {
        val previous = pressure(
            total = 29_500,
            history = 20_500,
            tools = 3_000,
            currentUser = 2_000,
        )
        val current = pressure(
            total = 33_000,
            history = 24_000,
            tools = 3_000,
            currentUser = 2_000,
        )

        val assessment = assessWorkStepContext(
            current = current,
            previous = previous,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )

        assertEquals(LocalWorkStepContextStatus.COMPACT, assessment.status)
        assertTrue(assessment.recommendsCompaction)
        assertTrue(assessment.effectiveProjectionTriggerTokens in 30_000 until 36_000)
        assertTrue("history_dominant" in assessment.reasons)
        assertTrue("history_growing_fast" in assessment.reasons)
        assertTrue("adaptive_history_pressure" in assessment.reasons)
    }

    @Test
    fun stableLargePrefixWaitsForNormalTriggerToProtectCacheReuse() {
        val previous = pressure(
            total = 33_000,
            history = 24_000,
            tools = 3_000,
            currentUser = 2_000,
        )
        val current = previous.copy()

        val assessment = assessWorkStepContext(
            current = current,
            previous = previous,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )

        assertEquals(LocalWorkStepContextStatus.WATCH, assessment.status)
        assertFalse(assessment.recommendsCompaction)
        assertEquals(36_000, assessment.effectiveProjectionTriggerTokens)
        assertEquals(0, assessment.historyGrowthTokens)
    }

    @Test
    fun heavyToolSchemaIsVisibleAsWatchSignalButDoesNotDeleteCapabilities() {
        val current = pressure(
            total = 26_000,
            history = 10_000,
            tools = 7_000,
            currentUser = 3_000,
        )

        val assessment = assessWorkStepContext(
            current = current,
            previous = null,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )

        assertEquals(LocalWorkStepContextStatus.WATCH, assessment.status)
        assertFalse(assessment.recommendsCompaction)
        assertTrue("tool_schema_heavy" in assessment.reasons)
    }

    @Test
    fun sourcePressureGrowthDoesNotCompareAgainstProjectedRequestSize() {
        val previousSource = pressure(
            total = 39_000,
            history = 30_000,
            tools = 3_000,
            currentUser = 2_000,
        )
        val currentRequest = pressure(
            total = 31_000,
            history = 23_000,
            tools = 3_000,
            currentUser = 2_000,
        )
        val currentSource = pressure(
            total = 40_000,
            history = 31_000,
            tools = 3_000,
            currentUser = 2_000,
        )

        val assessment = assessWorkStepContext(
            current = currentRequest,
            previous = previousSource,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
            growthCurrent = currentSource,
        )

        assertEquals(1_000, assessment.inputGrowthTokens)
        assertEquals(1_000, assessment.historyGrowthTokens)
        assertFalse(assessment.recommendsCompaction)
        assertFalse("history_growing_fast" in assessment.reasons)
    }

    @Test
    fun cacheSensitiveRouteWaitsForAbsoluteTriggerEvenDuringRapidGrowth() {
        val previous = pressure(
            total = 29_000,
            history = 21_000,
            tools = 3_000,
            currentUser = 2_000,
        )
        val current = pressure(
            total = 33_500,
            history = 25_500,
            tools = 3_000,
            currentUser = 2_000,
        )

        val assessment = assessWorkStepContext(
            current = current,
            previous = previous,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
            allowAdaptiveEarlyCompaction = false,
        )

        assertFalse(assessment.recommendsCompaction)
        assertEquals(LocalWorkStepContextStatus.WATCH, assessment.status)
        assertFalse("adaptive_history_pressure" in assessment.reasons)
    }

    @Test
    fun absolutePressureStillCompactsWithoutPreviousStep() {
        val current = pressure(
            total = 37_000,
            history = 18_000,
            tools = 4_000,
            currentUser = 3_000,
        )

        val assessment = assessWorkStepContext(
            current = current,
            previous = null,
            targetTokens = 28_000,
            baseTriggerTokens = 36_000,
        )

        assertEquals(LocalWorkStepContextStatus.COMPACT, assessment.status)
        assertTrue(assessment.recommendsCompaction)
        assertTrue("absolute_pressure" in assessment.reasons)
    }

    private fun pressure(
        total: Int,
        history: Int,
        tools: Int,
        currentUser: Int,
    ): LocalPromptPressure {
        val system = (total - history - tools - currentUser).coerceAtLeast(0)
        return LocalPromptPressure(
            contextChars = total * 2,
            estimatedInputTokens = total,
            systemTokens = system,
            historyTokens = history,
            currentUserTokens = currentUser,
            toolDefinitionTokens = tools,
            operationalLimitTokens = 678_464,
        )
    }
}
