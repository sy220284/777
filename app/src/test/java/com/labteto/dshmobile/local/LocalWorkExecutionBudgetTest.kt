package com.labteto.dshmobile.local

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
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

        first.commit()
        advanceUntilIdle()
        val second = waiting.await()
        second.commit()

        assertEquals(260_000L, budget.snapshot().committedExposureTokens)
    }

    @Test
    fun explicitPreAdmissionFailureReleasesExposureAndRequestSlot() = runTest {
        val control = LocalWorkExecutionControl(
            budget = LocalWorkExecutionBudget(
                exposureLimitTokens = 120_000,
                pendingLimitTokens = 120_000,
                maxRequests = 1,
            ),
        )
        val messages = listOf(
            kotlinx.serialization.json.buildJsonObject {
                put("role", "user")
                put("content", "test")
            },
        )

        repeat(3) {
            val failure = runCatching {
                executeWithModelAdmission(
                    control = control,
                    profileId = "route",
                    model = "unknown",
                    baseUrl = "https://example.test",
                    messages = messages,
                    tools = kotlinx.serialization.json.JsonArray(emptyList()),
                ) {
                    throw LocalModelException(
                        code = "MODEL_NETWORK",
                        message = "connect failed",
                        retryable = true,
                        admissionState = com.labteto.dshmobile.local.model.LocalModelAdmissionState.NOT_SENT,
                    )
                }
            }.exceptionOrNull()
            assertTrue(failure is LocalModelException)
        }

        val snapshot = control.budget.snapshot()
        assertEquals(0L, snapshot.committedExposureTokens)
        assertEquals(0L, snapshot.pendingExposureTokens)
        assertEquals(0, snapshot.admittedRequests)
        assertEquals(0, snapshot.reservedRequests)
    }

    @Test
    fun maybeAdmittedFailureCommitsExposureAndConsumesRequestSlot() = runTest {
        val control = LocalWorkExecutionControl(
            budget = LocalWorkExecutionBudget(
                exposureLimitTokens = 500_000,
                pendingLimitTokens = 500_000,
                maxRequests = 2,
            ),
        )
        val messages = listOf(
            kotlinx.serialization.json.buildJsonObject {
                put("role", "user")
                put("content", "test")
            },
        )

        val failure = runCatching {
            executeWithModelAdmission(
                control = control,
                profileId = "route",
                model = "unknown",
                baseUrl = "https://example.test",
                messages = messages,
                tools = kotlinx.serialization.json.JsonArray(emptyList()),
            ) {
                throw LocalModelException(
                    code = "MODEL_NETWORK",
                    message = "unknown provider admission",
                    retryable = false,
                    admissionState = com.labteto.dshmobile.local.model.LocalModelAdmissionState.MAYBE_ADMITTED,
                    continuationEligible = true,
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is LocalModelException)

        val snapshot = control.budget.snapshot()
        assertTrue(snapshot.committedExposureTokens > 0L)
        assertEquals(1, snapshot.admittedRequests)
        assertEquals(0, snapshot.reservedRequests)
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
