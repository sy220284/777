package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun thirdPartyRouteWithSameModelNameDoesNotGuessOfficialWindow() {
        val budget = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "deepseek-flash",
            baseUrl = "https://api.example.com/v1",
        )

        assertNull(budget.maxHistoryTokens)
        assertNull(budget.outputReserveTokens)
        assertNull(budget.headroomTokens)
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
