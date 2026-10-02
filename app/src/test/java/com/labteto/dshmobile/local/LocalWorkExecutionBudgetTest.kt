package com.labteto.dshmobile.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkExecutionBudgetTest {
    @Test
    fun largeFirstRequestCanRunButConcurrentExposureIsBounded() {
        val budget = LocalWorkExecutionBudget(
            exposureLimitTokens = 500_000,
            pendingLimitTokens = 200_000,
            maxRequests = 10,
        )
        val first = budget.reserve(180_000)
        val error = runCatching { budget.reserve(80_000) }.exceptionOrNull() as LocalModelException

        assertEquals("WORK_BUDGET_BUSY", error.code)
        first.settle()

        val second = budget.reserve(80_000)
        second.settle()
        assertEquals(260_000L, budget.snapshot().committedExposureTokens)
    }

    @Test
    fun terminalPlanLimitOpensCircuitForSiblingRequests() {
        val breaker = LocalModelRouteCircuitBreaker()
        assertFalse(breaker.isOpen("route"))
        breaker.observeFailure(
            "route",
            LocalModelException(
                code = "CHATGPT_PLAN_LIMIT_REACHED",
                message = "limit",
                retryable = false,
            ),
        )
        assertTrue(breaker.isOpen("route"))
        val error = runCatching { breaker.requireClosed("route") }.exceptionOrNull() as LocalModelException
        assertEquals("MODEL_ROUTE_CIRCUIT_OPEN", error.code)
    }
}
