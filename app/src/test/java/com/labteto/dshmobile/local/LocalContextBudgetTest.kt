package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextBudgetTest {
    @Test
    fun officialDeepSeekRouteGetsModelWindowBudget() {
        val budget = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
        )

        assertNotNull(budget.maxHistoryTokens)
        assertNotNull(budget.outputReserveTokens)
        assertNotNull(budget.headroomTokens)
    }

    @Test
    fun unknownRouteGetsOperationalGuardWithoutInventingProviderWindow() {
        val budget = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "deepseek-flash",
            baseUrl = "https://api.example.com/v1",
        )

        assertNotNull(budget.maxHistoryTokens)
        assertTrue(budget.maxHistoryTokens!! > 0)
        assertEquals(0, budget.outputReserveTokens)
        assertNotNull(budget.headroomTokens)
        assertNull(documentedContextWindowTokens("deepseek-flash", "https://api.example.com/v1"))
    }

    @Test
    fun unknownRouteCanUseExplicitContextWindowWithoutOverridingOfficialPreset() {
        val custom = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "custom-model",
            baseUrl = "https://proxy.example/v1",
            contextWindowTokensOverride = 1_000_000,
        )
        val official = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
            contextWindowTokensOverride = 16_000,
        )

        assertEquals(1_000_000, documentedContextWindowTokens("custom-model", "https://proxy.example/v1", 1_000_000))
        assertTrue(custom.maxHistoryTokens!! > 192_000)
        assertEquals(1_000_000, documentedContextWindowTokens("deepseek-flash", "https://api.deepseek.com", 16_000))
        assertTrue(official.maxHistoryTokens!! > 16_000)
    }

    @Test
    fun toolResultBudgetIsSharedByEveryProductSurface() {
        val budget = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "deepseek-flash",
            baseUrl = "https://api.deepseek.com",
        )

        assertEquals(16_000, budget.maxToolResultTokens)
    }
}
