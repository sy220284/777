package com.labteto.dshmobile.local.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolPromptBudgetPolicyTest {
    @Test
    fun optionalToolsNeverExceedActualCapacityAfterCoreSchemas() {
        for (remaining in listOf(0, 128, 8_000, 16_000, 24_000, 100_000)) {
            for (core in listOf(0, 5_000, 16_000, 50_000)) {
                for (preserve in listOf(true, false)) {
                    val budget = remainingOptionalToolDefinitionBudget(
                        remainingOperationalTokens = remaining,
                        coreDefinitionTokens = core,
                        preserveToolSurface = preserve,
                    )
                    assertTrue(budget >= 0)
                    assertTrue(budget <= 16_000)
                    assertTrue(budget.toLong() + core <= remaining.toLong() || budget == 0)
                }
            }
        }
        assertEquals(0, remainingOptionalToolDefinitionBudget(6_000, 7_000, true))
        assertEquals(1_000, remainingOptionalToolDefinitionBudget(7_000, 3_000, false))
        assertEquals(4_000, remainingOptionalToolDefinitionBudget(7_000, 3_000, true))
        assertEquals(24_000, remainingOptionalToolDefinitionBudget(
            remainingOperationalTokens = 150_000,
            coreDefinitionTokens = 12_000,
            preserveToolSurface = true,
            requestedDefinitionTokens = 24_000,
        ))
        assertEquals(3_000, remainingOptionalToolDefinitionBudget(
            remainingOperationalTokens = 20_000,
            coreDefinitionTokens = 8_000,
            preserveToolSurface = false,
            requestedDefinitionTokens = 24_000,
        ))
    }
}
