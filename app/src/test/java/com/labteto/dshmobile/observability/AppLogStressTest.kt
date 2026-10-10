package com.labteto.dshmobile.observability

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStressTest {
    @Test
    fun persistenceQueueStaysBoundedDuringLogStorm() {
        val executor = createAppLogPersistenceExecutor(maxQueued = 2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val persisted = AtomicInteger()
        try {
            executor.execute {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            repeat(1_000) {
                executor.execute { persisted.incrementAndGet() }
            }

            assertTrue(executor.queue.size <= 2)
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1_000, persisted.get())
        }
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
            createAppLogPersistenceExecutor(0)
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
