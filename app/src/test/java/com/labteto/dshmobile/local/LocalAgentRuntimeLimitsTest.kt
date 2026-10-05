package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.settings.LocalAgentRuntimeLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAgentRuntimeLimitsTest {
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
        assertEquals(1, LocalAgentRuntimeLimits.normalizeModelAttempts(0))
        assertEquals(3, LocalAgentRuntimeLimits.normalizeModelAttempts(3))
        assertEquals(5, LocalAgentRuntimeLimits.normalizeModelAttempts(99))
    }
}
