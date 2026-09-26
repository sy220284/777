package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
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
            usageMode = LocalUsageMode.WORK,
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
            usageMode = LocalUsageMode.WORK,
            model = "deepseek-flash",
            baseUrl = "https://api.example.com/v1",
        )

        assertNull(budget.maxHistoryTokens)
        assertNull(budget.outputReserveTokens)
        assertNull(budget.headroomTokens)
    }

    @Test
    fun chatKeepsSmallerToolResultBudgetThanWork() {
        val work = localHistoryBudgetFor(
            512,
            HarnessResourcePressure.LOW,
            LocalUsageMode.WORK,
            "deepseek-flash",
            "https://api.deepseek.com",
        )
        val chat = localHistoryBudgetFor(
            512,
            HarnessResourcePressure.LOW,
            LocalUsageMode.CHAT,
            "deepseek-flash",
            "https://api.deepseek.com",
        )

        assertTrue(chat.maxToolResultTokens < work.maxToolResultTokens)
    }
}
