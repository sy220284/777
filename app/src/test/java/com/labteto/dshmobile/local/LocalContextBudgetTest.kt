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
    fun modernPhoneHistoryRetentionIsRamAwareButModelWindowRemainsAuthoritative() {
        fun budget(ram: Int, heap: Int = 512, pressure: HarnessResourcePressure = HarnessResourcePressure.LOW) =
            localHistoryBudgetFor(
                memoryClassMb = heap,
                pressure = pressure,
                model = "custom-model",
                baseUrl = "https://proxy.example/v1",
                deviceRamMb = ram,
            )

        assertEquals(700_000, budget(0).maxHistoryChars) // Unknown RAM: legacy heap guard.
        val twelve = budget(11_200, heap = 384)
        val sixteen = budget(15_200)
        assertEquals(900_000, twelve.maxHistoryChars)
        assertEquals(1_100_000, sixteen.maxHistoryChars)
        assertEquals(320_000, budget(15_200, heap = 192).maxHistoryChars)
        assertTrue(budget(15_200, pressure = HarnessResourcePressure.HIGH).maxHistoryChars < sixteen.maxHistoryChars)
        assertEquals(twelve.maxHistoryTokens, sixteen.maxHistoryTokens)
        assertEquals(192_000, sixteen.maxHistoryTokens)
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
