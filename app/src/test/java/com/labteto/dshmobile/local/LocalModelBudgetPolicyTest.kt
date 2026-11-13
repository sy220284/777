package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.local.model.LocalModelAuthKind
import com.labteto.dshmobile.local.model.LocalModelBudgetPolicies
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelProtocol
import com.labteto.dshmobile.local.work.LocalWorkExecutionBudget
import com.labteto.dshmobile.local.work.workRequestProjectionTargetTokens
import com.labteto.dshmobile.local.work.workRequestProjectionTriggerTokens
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelBudgetPolicyTest {
    @Test
    fun officialM3UsesLargerChatHistoryAndWorkProjectionThanGenericRoutes() {
        val official = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "MiniMax-M3",
            baseUrl = "https://api.minimax.io/v1",
        )
        val generic = localHistoryBudgetFor(
            memoryClassMb = 512,
            pressure = HarnessResourcePressure.LOW,
            model = "MiniMax-M3",
            baseUrl = "https://proxy.example/v1",
        )
        assertEquals(1_400_000, official.maxHistoryChars)
        assertEquals(700_000, generic.maxHistoryChars)
        assertEquals(800_000, official.maxHistoryTokens)
        assertEquals(192_000, generic.maxHistoryTokens)
        val cache = LocalModelPresets.promptCachePolicyFor(
            model = "MiniMax-M3",
            baseUrl = "https://api.minimax.io/v1",
            protocol = LocalModelProtocol.CHAT_COMPLETIONS,
            authKind = LocalModelAuthKind.API_KEY,
        )
        assertFalse(cache.allowAdaptiveEarlyCompaction)
        assertTrue(cache.preserveToolSurface)
        assertEquals(160_000, workRequestProjectionTargetTokens(800_000, cache))
        assertEquals(240_000, workRequestProjectionTriggerTokens(800_000, cache))
        assertEquals(36_000, workRequestProjectionTriggerTokens(192_000))
    }

    @Test
    fun differentModelsAndResourcePressureKeepIndependentBudgets() {
        val m2 = LocalModelBudgetPolicies.forRoute("MiniMax-M2.7", "https://api.minimaxi.com/v1")
        val m3 = LocalModelBudgetPolicies.forRoute("MiniMax-M3.1-Flash-Preview", "https://api.minimax.cn/anthropic/v1")
        val thirdParty = LocalModelBudgetPolicies.forRoute("MiniMax-M3", "https://proxy.example/v1")
        assertEquals(160, m2.workRequestsPerSlice)
        assertEquals(256, m3.workRequestsPerSlice)
        val stable = LocalModelBudgetPolicies.forRoute("MiniMax-M3.1-Flash", "https://api.minimaxi.com/v1")
        assertEquals(m3, stable)
        assertEquals(1_000_000L, stable.pendingWorkTokens)
        assertEquals(128, thirdParty.workRequestsPerSlice)
        assertEquals(512_000L, thirdParty.pendingWorkTokens)
        assertEquals(160, LocalModelBudgetPolicies.forRoute("MiniMax-M2.5", "https://api.minimax.io/v1").workRequestsPerSlice)
        val high = localHistoryBudgetFor(
            512, HarnessResourcePressure.HIGH, "MiniMax-M3", "https://api.minimax.io/v1",
        )
        val low = localHistoryBudgetFor(
            512, HarnessResourcePressure.LOW, "MiniMax-M3", "https://api.minimax.io/v1",
        )
        assertTrue(high.maxHistoryChars < low.maxHistoryChars)
        assertEquals(low.maxHistoryTokens, high.maxHistoryTokens)
    }

    @Test
    fun sliceBudgetsFollowSelectedModelWithoutDroppingPriorUsage() = runTest {
        val budget = LocalWorkExecutionBudget()
        budget.beginExecutionSlice(LocalModelBudgetPolicies.forRoute("MiniMax-M3", "https://api.minimaxi.com/v1"))
        assertEquals(256, budget.snapshot().maxRequests)
        repeat(65) { budget.reserve(1).commit(reportedInputTokens = 1) }
        assertEquals(65L, budget.snapshot().reportedExposureTokens)
        budget.beginExecutionSlice(LocalModelBudgetPolicies.forRoute("gpt-test", "https://example.com/v1"))
        assertEquals(128, budget.snapshot().maxRequests)
        assertEquals(65L, budget.snapshot().reportedExposureTokens)
        repeat(128) { budget.reserve(1).commit() }
        val exhausted = runCatching { budget.reserve(1) }.exceptionOrNull() as LocalModelException
        assertEquals("WORK_BUDGET_EXHAUSTED", exhausted.code)
    }
}
