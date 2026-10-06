package com.labteto.dshmobile.automation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebhookExecutionTest {

    @Test fun persistedWebhookStatusKeepsExistingWireValue() {
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString(
            WebhookRunResult.serializer(),
            """{"requestId":"r1","status":"queued","updatedAt":1}""",
        )

        assertEquals(WebhookRunStatus.QUEUED, decoded.status)
        assertTrue(
            json.encodeToString(WebhookRunResult.serializer(), decoded)
                .contains("\"status\":\"queued\""),
        )
    }

    @Test fun unknownPersistedWebhookStatusDegradesToFailedOnDowngrade() {
        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString(
            WebhookRunResult.serializer(),
            """{"requestId":"r-future","status":"future_waiting","updatedAt":1}""",
        )

        assertEquals(WebhookRunStatus.FAILED, decoded.status)
        assertTrue(
            json.encodeToString(WebhookRunResult.serializer(), decoded)
                .contains("\"status\":\"failed\""),
        )
    }

    @Test fun cancellationWhileRunningIsRecordedAndPropagated() = runTest {
        val states = mutableListOf<WebhookRunStatus>()
        val job = launch { executeWebhookRun({ state, _, _ -> states += state }) { awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf(WebhookRunStatus.RUNNING, WebhookRunStatus.CANCELLED), states)
    }
    @Test fun cancellationBeforeWorkCompletesSettlesWithoutRetry() = runTest {
        val states = mutableListOf<WebhookRunStatus>()
        val job = launch {
            executeWebhookRun({ state, _, _ -> states += state }) {
                awaitCancellation()
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf(WebhookRunStatus.RUNNING, WebhookRunStatus.CANCELLED), states)
    }
    @Test fun successfulRunStoresResult() = runTest {
        val states = mutableListOf<WebhookRunStatus>()
        var output: String? = null
        executeWebhookRun({ state, result, _ -> states += state; output = result }) { "done" }
        assertEquals(listOf(WebhookRunStatus.RUNNING, WebhookRunStatus.COMPLETED), states)
        assertEquals("done", output)
    }

    @Test fun independentWebhookRunsMayOverlapAndAllSettle() = runTest {
        var active = 0
        var maxActive = 0
        val completed = mutableSetOf<Int>()
        val jobs = (0 until 8).map { index ->
            launch {
                executeWebhookRun(
                    update = { state, _, _ ->
                        if (state == WebhookRunStatus.COMPLETED) completed += index
                    },
                ) {
                    active += 1
                    maxActive = maxOf(maxActive, active)
                    try {
                        delay(1)
                        "done-$index"
                    } finally {
                        active -= 1
                    }
                }
            }
        }

        runCurrent()
        assertTrue(maxActive > 1)
        advanceUntilIdle()
        jobs.forEach { assertTrue(it.isCompleted) }
        assertEquals(0, active)
        assertEquals((0 until 8).toSet(), completed)
    }

    @Test fun failedRunDoesNotPoisonNextQueuedRun() = runTest {
        val firstStates = mutableListOf<WebhookRunStatus>()
        val secondStates = mutableListOf<WebhookRunStatus>()

        val first = launch {
            executeWebhookRun({ state, _, _ -> firstStates += state }) {
                error("boom")
            }
        }
        val second = launch {
            executeWebhookRun({ state, _, _ -> secondStates += state }) {
                "ok"
            }
        }

        advanceUntilIdle()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(listOf(WebhookRunStatus.RUNNING, WebhookRunStatus.FAILED), firstStates)
        assertEquals(listOf(WebhookRunStatus.RUNNING, WebhookRunStatus.COMPLETED), secondStates)
    }


    @Test fun executionLimiterRejectsOverflowAndRecoversCapacity() {
        val limiter = WebhookExecutionLimiter(3)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(3, limiter.pendingCount())

        assertTrue(limiter.release())
        assertEquals(2, limiter.pendingCount())
        assertTrue(limiter.tryAcquire())
        assertEquals(3, limiter.pendingCount())
    }

    @Test fun executionLimiterNeverUnderflowsOnExtraRelease() {
        val limiter = WebhookExecutionLimiter(1)

        assertFalse(limiter.release())
        assertEquals(0, limiter.pendingCount())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.release())
        assertFalse(limiter.release())
        assertEquals(0, limiter.pendingCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun executionLimiterRejectsZeroCapacity() {
        WebhookExecutionLimiter(0)
    }


    @Test fun queuedCancellationCompletionIsRecognized() {
        assertTrue(
            shouldMarkWebhookQueuedCancellation(
                cause = kotlinx.coroutines.CancellationException("stop"),
                currentStatus = WebhookRunStatus.QUEUED,
            ),
        )
        assertFalse(
            shouldMarkWebhookQueuedCancellation(
                cause = kotlinx.coroutines.CancellationException("stop"),
                currentStatus = WebhookRunStatus.RUNNING,
            ),
        )
        assertFalse(
            shouldMarkWebhookQueuedCancellation(
                cause = IllegalStateException("boom"),
                currentStatus = WebhookRunStatus.QUEUED,
            ),
        )
    }

}
