package com.labteto.dshmobile.observability

import java.util.concurrent.Executor
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStressTest {
    @Test
    fun persistenceQueueBatchesWithoutRunningDiskWorkOnProducer() {
        val scheduled = ArrayDeque<Runnable>()
        val written = mutableListOf<Int>()
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { batch: List<Int> -> written.addAll(batch) },
            important = { it < 0 },
            capacity = 2_000,
            batchSize = 100,
        )
        repeat(1_000) { queue.offer(it) }
        assertEquals(1, scheduled.size)
        assertTrue(written.isEmpty())
        assertEquals(1_000, queue.snapshot().outstanding.size)
        scheduled.removeFirst().run()
        assertEquals((0..999).toList(), written)
        assertEquals(0, queue.snapshot().outstanding.size)
    }

    @Test
    fun failedBatchStaysExportableAndCanRetryLater() {
        val scheduled = ArrayDeque<Runnable>()
        val written = mutableListOf<Int>()
        var diskBroken = true
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { batch: List<Int> ->
                if (diskBroken) throw java.io.IOException("disk broken")
                written.addAll(batch)
            },
            important = { it < 0 },
            capacity = 10,
        )
        queue.offer(-1)
        scheduled.removeFirst().run()
        assertEquals(listOf(-1), queue.snapshot().outstanding)
        assertEquals("IOException", queue.snapshot().writeError)
        diskBroken = false
        queue.offer(2)
        scheduled.removeFirst().run()
        assertEquals(listOf(-1, 2), written)
        assertEquals(null, queue.snapshot().writeError)
    }

    @Test
    fun overflowPreservesFailureAndReportsMissingRows() {
        val scheduled = ArrayDeque<Runnable>()
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { _: List<Int> -> },
            important = { it < 0 },
            capacity = 3,
        )
        repeat(3) { queue.offer(it) }
        queue.offer(-1)
        val state = queue.snapshot()
        assertEquals(3, state.outstanding.size)
        assertTrue(state.outstanding.contains(-1))
        assertEquals(1L, state.overflow)
    }

    @Test
    fun normalLogStormCannotEvictOnlyFailureRecords() {
        val scheduled = ArrayDeque<Runnable>()
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { _: List<Int> -> },
            important = { it < 0 },
            capacity = 2,
        )
        queue.offer(-1)
        queue.offer(-2)
        queue.offer(100)
        assertEquals(listOf(-1, -2), queue.snapshot().outstanding)
        assertEquals(1L, queue.snapshot().overflow)
    }

    @Test
    fun overflowRetainsOriginalFailureAndMostRecentFailure() {
        val scheduled = ArrayDeque<Runnable>()
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { _: List<Int> -> },
            important = { it < 0 },
            capacity = 3,
        )
        queue.offer(-1)
        queue.offer(-2)
        queue.offer(-3)
        queue.offer(-4)
        queue.offer(-5)
        assertEquals(listOf(-1, -4, -5), queue.snapshot().outstanding)
        assertEquals(2L, queue.snapshot().overflow)
    }

    @Test
    fun failedBatchRetriesWithoutAnotherLogEntry() {
        val scheduled = ArrayDeque<Runnable>()
        val retries = ArrayDeque<Runnable>()
        val written = mutableListOf<Int>()
        var failure = true
        val queue = DiagnosticWriteQueue(
            executor = Executor { scheduled.addLast(it) },
            writer = { batch: List<Int> ->
                if (failure) throw java.io.IOException("write failed")
                written.addAll(batch)
            },
            important = { it < 0 },
            scheduleRetry = { retries.addLast(it) },
        )
        queue.offer(-42)
        scheduled.removeFirst().run()
        assertEquals(listOf(-42), queue.snapshot().outstanding)
        assertEquals(1, retries.size)
        assertEquals("IOException", queue.snapshot().writeError)
        failure = false
        retries.removeFirst().run()
        assertEquals(listOf(-42), written)
        assertTrue(queue.snapshot().outstanding.isEmpty())
        assertEquals(null, queue.snapshot().writeError)
    }

    @Test
    fun inMemoryLogIsSanitizedBeforeExportAndKeepsOriginalThrowableType() {
        AppLog.clear()
        AppLog.warn(
            "security",
            "Authorization: Bearer super-secret",
            IllegalStateException("api_key=hidden"),
        )

        val entry = AppLog.snapshot().last()
        assertTrue(entry.message.contains("<redacted>"))
        assertTrue(!entry.message.contains("super-secret"))
        assertEquals("IllegalStateException", entry.throwableType)
        assertTrue(entry.throwableMessage.orEmpty().contains("<redacted>"))
        assertTrue(!entry.throwableMessage.orEmpty().contains("hidden"))
    }

    @Test
    fun caughtExceptionKeepsOperationIdentityAndSanitizedNestedCause() {
        AppLog.clear()
        AppLog.failure(
            "Model", "request_model",
            IllegalStateException("request failed", IllegalArgumentException("access_token=super-secret")),
            sessionId = "session-12", requestId = "req-12", runId = "run-12",
        )
        val entry = AppLog.snapshot().last()
        assertTrue(entry.message.contains("operation=request_model"))
        assertTrue(entry.message.contains("session_id=session-12"))
        assertTrue(entry.message.contains("request_id=req-12"))
        assertTrue(entry.message.contains("run_id=run-12"))
        assertTrue(entry.throwableCauseChain.orEmpty().contains("IllegalArgumentException"))
        assertTrue(entry.throwableCauseChain.orEmpty().contains("<redacted>"))
        assertTrue(!entry.throwableCauseChain.orEmpty().contains("super-secret"))
    }

    @Test
    fun persistenceQueueRejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticWriteQueue(
                executor = Executor { },
                writer = { _: List<Int> -> },
                important = { false },
                capacity = 0,
            )
        }
    }

    @Test
    fun inMemoryTailRemainsBoundedUnderHighVolume() {
        AppLog.clear()
        repeat(5_000) { index ->
            AppLog.info("stress", "entry-$index")
        }

        val snapshot = AppLog.snapshot()
        assertEquals(200, snapshot.size)
        assertEquals("entry-4999", snapshot.last().message)
    }
}
