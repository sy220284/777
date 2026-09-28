package com.labteto.dshmobile.automation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebhookExecutionTest {
    @Test fun cancellationWhileRunningIsRecordedAndPropagated() = runTest {
        val states = mutableListOf<String>()
        val job = launch { executeWebhookRun(Mutex(), { state, _, _ -> states += state }) { awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("running", "cancelled"), states)
    }
    @Test fun queuedCancellationDoesNotRunPrompt() = runTest {
        val states = mutableListOf<String>()
        val mutex = Mutex(locked = true)
        val job = launch { executeWebhookRun(mutex, { state, _, _ -> states += state }) { error("must not run") } }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(listOf("cancelled"), states)
    }
    @Test fun successfulRunStoresResult() = runTest {
        val states = mutableListOf<String>()
        var output: String? = null
        executeWebhookRun(Mutex(), { state, result, _ -> states += state; output = result }) { "done" }
        assertEquals(listOf("running", "completed"), states)
        assertEquals("done", output)
    }

    @Test fun manyQueuedRunsNeverOverlapAndAllSettle() = runTest {
        val mutex = Mutex()
        var active = 0
        var maxActive = 0
        val completed = mutableSetOf<Int>()
        val jobs = (0 until 64).map { index ->
            launch {
                executeWebhookRun(
                    mutex,
                    update = { state, _, _ ->
                        if (state == "completed") completed += index
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

        advanceUntilIdle()
        jobs.forEach { assertTrue(it.isCompleted) }
        assertEquals(1, maxActive)
        assertEquals(0, active)
        assertEquals((0 until 64).toSet(), completed)
    }

    @Test fun failedRunDoesNotPoisonNextQueuedRun() = runTest {
        val mutex = Mutex()
        val firstStates = mutableListOf<String>()
        val secondStates = mutableListOf<String>()

        val first = launch {
            executeWebhookRun(mutex, { state, _, _ -> firstStates += state }) {
                error("boom")
            }
        }
        val second = launch {
            executeWebhookRun(mutex, { state, _, _ -> secondStates += state }) {
                "ok"
            }
        }

        advanceUntilIdle()
        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(listOf("running", "failed"), firstStates)
        assertEquals(listOf("running", "completed"), secondStates)
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

}
