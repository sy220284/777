package com.labteto.dshmobile.local

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkExecutionBudgetTest {
    @Test
    fun largeFirstRequestCanRunButConcurrentExposureWaitsInsteadOfFailing() = runTest {
        val budget = LocalWorkExecutionBudget(
            exposureLimitTokens = 500_000,
            pendingLimitTokens = 200_000,
            maxRequests = 10,
        )
        val first = budget.reserve(180_000)
        val waiting = async { budget.reserve(80_000) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        first.settle()
        advanceUntilIdle()
        val second = waiting.await()
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
