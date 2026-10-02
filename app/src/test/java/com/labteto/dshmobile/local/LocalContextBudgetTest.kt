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
