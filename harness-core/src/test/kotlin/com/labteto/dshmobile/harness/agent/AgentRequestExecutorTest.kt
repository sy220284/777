package com.labteto.dshmobile.harness.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRequestExecutorTest {
    @Test
    fun retryableFailureIsObservedThenRetried() = runTest {
        val events = mutableListOf<AgentRequestEvent>()
        val delays = mutableListOf<Long>()
        var calls = 0
        val executor = AgentRequestExecutor(
            maxAttempts = 3,
            retryable = { it is IOException },
            eventSink = AgentRequestEventSink { events += it },
            backoffMillis = { 250L * it },
            sleeper = { delays += it },
        )

        val result = executor.execute { attempt ->
            calls += 1
            if (attempt < 3) throw IOException("temporary-$attempt")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(250L, 500L), delays)
        assertEquals(3, events.count { it is AgentRequestEvent.AttemptStarted })
        assertEquals(2, events.count { it is AgentRequestEvent.AttemptFailed })
        assertEquals(2, events.count { it is AgentRequestEvent.RetryScheduled })
        assertTrue(events.last() is AgentRequestEvent.AttemptSucceeded)
    }

    @Test
    fun providerRetryDelayOverridesLocalBackoff() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        val providerError = IOException("rate limited")
        val executor = AgentRequestExecutor(
            maxAttempts = 2,
            retryable = { it === providerError },
            providerRetryDelayMillis = { error, _ -> if (error === providerError) 9_000L else null },
            backoffMillis = { 250L },
            sleeper = { delays += it },
        )

        val result = executor.execute { attempt ->
            calls += 1
            if (attempt == 1) throw providerError
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(listOf(9_000L), delays)
    }

    @Test
    fun nonRetryableFailureStopsImmediately() = runTest {
        val events = mutableListOf<AgentRequestEvent>()
        var calls = 0
        val executor = AgentRequestExecutor(
            maxAttempts = 5,
            retryable = { it is IOException },
            eventSink = AgentRequestEventSink { events += it },
            sleeper = { error("不应等待") },
        )

        runCatching {
            executor.execute<String> {
                calls += 1
                error("fatal")
            }
        }

        assertEquals(1, calls)
        assertEquals(1, events.count { it is AgentRequestEvent.AttemptFailed })
        assertFalse(events.any { it is AgentRequestEvent.RetryScheduled })
    }

    @Test
    fun cancellationNeverBecomesRetry() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentRequestEvent>()
        val executor = AgentRequestExecutor(
            maxAttempts = 5,
            retryable = { true },
            eventSink = AgentRequestEventSink { events += it },
        )

        val job = async {
            executor.execute<String> {
                gate.await()
                "late"
            }
        }
        runCurrent()
        job.cancel(CancellationException("stop"))
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(1, events.count { it is AgentRequestEvent.AttemptStarted })
        assertEquals(1, events.count { it is AgentRequestEvent.AttemptCancelled })
        assertFalse(events.any { it is AgentRequestEvent.AttemptFailed })
        assertFalse(events.any { it is AgentRequestEvent.RetryScheduled })
    }
}
