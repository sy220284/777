package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalWorkExecutionBudgetTest {
    @Before
    fun resetRouteHealth() {
        LocalModelRouteHealth.resetForTest()
    }

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
                    routeFingerprint = "route",
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
                routeFingerprint = "route",
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
    fun reportedUsageCalibratesLaterUncertainExposure() = runTest {
        val budget = LocalWorkExecutionBudget(
            exposureLimitTokens = 500_000,
            pendingLimitTokens = 500_000,
            maxRequests = 10,
        )

        budget.reserve(100_000).commit(reportedInputTokens = 50_000)
        budget.reserve(100_000).commit()

        val snapshot = budget.snapshot()
        assertEquals(50_000L, snapshot.reportedExposureTokens)
        assertEquals(50_000L, snapshot.uncertainExposureTokens)
        assertEquals(100_000L, snapshot.committedExposureTokens)
        assertEquals(500, snapshot.estimateCalibrationPermille)
        assertEquals(1, snapshot.calibrationSamples)
    }

    @Test
    fun calibrationIsIsolatedByModelRoute() = runTest {
        val budget = LocalWorkExecutionBudget(
            exposureLimitTokens = 500_000,
            pendingLimitTokens = 500_000,
            maxRequests = 10,
        )
        budget.reserve(100_000, "route-a").commit(reportedInputTokens = 50_000)
        val routeB = budget.reserve(100_000, "route-b")

        val snapshot = budget.snapshot()
        assertEquals(100_000L, snapshot.pendingExposureTokens)
        assertEquals(1, snapshot.calibrationRoutes)
        routeB.release()
    }

    @Test
    fun exposureOverlapWaitsWhenOnlyPendingReservationCausesTheLimit() = runTest {
        val budget = LocalWorkExecutionBudget(
            exposureLimitTokens = 200_000,
            pendingLimitTokens = 200_000,
            maxRequests = 10,
        )
        val first = budget.reserve(140_000)
        val waiting = async { budget.reserve(80_000) }
        runCurrent()
        assertFalse(waiting.isCompleted)

        first.release()
        advanceUntilIdle()
        val second = waiting.await()
        second.commit(reportedInputTokens = 60_000)

        val snapshot = budget.snapshot()
        assertEquals(60_000L, snapshot.reportedExposureTokens)
        assertEquals(60_000L, snapshot.committedExposureTokens)
    }

    @Test
    fun cancellationBeforeRequestBodyIsSentReleasesBudgetAndRequestSlot() = runTest {
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

        val failure = runCatching {
            executeWithModelAdmission(
                control = control,
                routeFingerprint = "route",
                model = "unknown",
                baseUrl = "https://example.test",
                messages = messages,
                tools = kotlinx.serialization.json.JsonArray(emptyList()),
            ) {
                throw LocalModelCancellationException(
                    LocalModelAdmissionState.NOT_SENT,
                    CancellationException("cancel before send"),
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)

        val snapshot = control.budget.snapshot()
        assertEquals(0L, snapshot.committedExposureTokens)
        assertEquals(0L, snapshot.pendingExposureTokens)
        assertEquals(0, snapshot.admittedRequests)
        assertEquals(0, snapshot.reservedRequests)
    }

    @Test
    fun cancellationAfterRequestBodyMayBeAdmittedKeepsUncertainExposure() = runTest {
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
                routeFingerprint = "route",
                model = "unknown",
                baseUrl = "https://example.test",
                messages = messages,
                tools = kotlinx.serialization.json.JsonArray(emptyList()),
            ) {
                throw LocalModelCancellationException(
                    LocalModelAdmissionState.MAYBE_ADMITTED,
                    CancellationException("cancel after send"),
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)

        val snapshot = control.budget.snapshot()
        assertTrue(snapshot.uncertainExposureTokens > 0L)
        assertEquals(1, snapshot.admittedRequests)
        assertEquals(0, snapshot.reservedRequests)
    }

    @Test
    fun repeatedTransientFailuresOpenCooldownBeforeBudgetOrProviderCall() {
        val breaker = LocalModelRouteCircuitBreaker()

        repeat(3) {
            breaker.acquire("route").failure(streamInterrupted())
        }

        assertTrue(breaker.isOpen("route"))
        val error = runCatching { breaker.acquire("route") }.exceptionOrNull() as LocalModelException
        assertEquals("MODEL_ROUTE_CIRCUIT_COOLDOWN", error.code)
        assertEquals("route_circuit_cooldown", com.labteto.dshmobile.local.model.modelFailureKind(error))
        assertFalse(error.retryable)
    }

    @Test
    fun cooldownExpiryAllowsExactlyOneHalfOpenProbe() {
        var now = 1_000L
        LocalModelRouteHealth.resetForTest { now }
        val breaker = LocalModelRouteCircuitBreaker()
        repeat(3) { breaker.acquire("route").failure(streamInterrupted()) }

        now += 91_000L
        val probe = breaker.acquire("route")
        val concurrent = runCatching { breaker.acquire("route") }.exceptionOrNull() as LocalModelException
        assertEquals("MODEL_ROUTE_CIRCUIT_COOLDOWN", concurrent.code)

        val first = LocalModelRouteHealth.activeCooldown("route")
        probe.failure(streamInterrupted())
        val second = LocalModelRouteHealth.activeCooldown("route")

        assertTrue(first?.probeInFlight == true)
        assertEquals(180_000L, second?.millis)
        assertTrue(second?.probeInFlight == false)
    }

    @Test
    fun successfulRequestClearsTransientHealthAcrossRuns() {
        val firstRun = LocalModelRouteCircuitBreaker()
        val inFlightSuccess = firstRun.acquire("route")
        repeat(3) { firstRun.acquire("route").failure(streamInterrupted()) }
        assertTrue(firstRun.isOpen("route"))

        inFlightSuccess.success()

        val nextRun = LocalModelRouteCircuitBreaker()
        assertFalse(nextRun.isOpen("route"))
        assertEquals(0, LocalModelRouteHealth.consecutiveFailures("route"))
        nextRun.acquire("route").release()
    }

    @Test
    fun releasingCancelledHalfOpenProbeDoesNotCountAnotherFailure() {
        var now = 1_000L
        LocalModelRouteHealth.resetForTest { now }
        val breaker = LocalModelRouteCircuitBreaker()
        repeat(3) { breaker.acquire("route").failure(streamInterrupted()) }
        now += 91_000L

        breaker.acquire("route").release()

        assertEquals(3, LocalModelRouteHealth.consecutiveFailures("route"))
        assertFalse(breaker.isOpen("route"))
        breaker.acquire("route").release()
    }

    @Test
    fun routeHealthRegistryStaysBoundedUnderRouteChurn() {
        val breaker = LocalModelRouteCircuitBreaker()
        repeat(400) { index ->
            breaker.acquire("route-$index").failure(streamInterrupted())
        }
        assertTrue(LocalModelRouteHealth.trackedRoutesForTest() <= 256)
    }

    @Test
    fun terminalPlanLimitOpensCircuitForSiblingRequests() {
        val breaker = LocalModelRouteCircuitBreaker()
        assertFalse(breaker.isOpen("route"))
        breaker.acquire("route").failure(
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

    private fun streamInterrupted() = LocalModelException(
        code = "CHATGPT_PLAN_STREAM_INTERRUPTED",
        message = "stream was reset: CANCEL",
        retryable = false,
        admissionState = LocalModelAdmissionState.ADMITTED,
        continuationEligible = true,
    )
}
