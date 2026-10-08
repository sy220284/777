package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.LocalAgentRuntimeLimits
import com.labteto.dshmobile.local.agent.LocalAgentRuntimeSettings
import com.labteto.dshmobile.local.model.LocalModelConfigContract
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentRuntimeLimitsTest {
    @Test
    fun freshAgentDefaultsAre128StepsForBothLoops() {
        assertEquals(128, LocalAgentRuntimeSettings.DEFAULT_MAIN_MAX_STEPS)
        assertEquals(128, LocalAgentRuntimeSettings.DEFAULT_SUBAGENT_MAX_STEPS)
    }

    @Test
    fun configuredStepsCanCrossLegacy128Limit() {
        assertEquals(256, LocalAgentRuntimeLimits.normalizeMainSteps(256))
        assertEquals(256, LocalAgentRuntimeLimits.normalizeSubagentSteps(256))
    }

    @Test
    fun configuredStepsUseSharedSafetyBoundary() {
        assertEquals(4, LocalAgentRuntimeLimits.normalizeMainSteps(Int.MIN_VALUE))
        assertEquals(1, LocalAgentRuntimeLimits.normalizeSubagentSteps(Int.MIN_VALUE))
        assertEquals(512, LocalAgentRuntimeLimits.normalizeMainSteps(Int.MAX_VALUE))
        assertEquals(512, LocalAgentRuntimeLimits.normalizeSubagentSteps(Int.MAX_VALUE))
    }

    @Test
    fun modelAttemptsKeepExistingBoundary() {
        assertEquals(1, LocalModelConfigContract.normalizeModelAttempts(0))
        assertEquals(3, LocalModelConfigContract.normalizeModelAttempts(3))
        assertEquals(5, LocalModelConfigContract.normalizeModelAttempts(99))
    }
}
