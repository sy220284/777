package com.labteto.dshmobile.local.model

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelStreamWatchdogTest {
    @Test
    fun startupDeadlineAndGenerationDeadlineAreIndependent() {
        var time = 0L
        var cancels = 0
        val progress = LocalModelStreamProgressMonitor(
            timeoutNanos = TimeUnit.MINUTES.toNanos(8),
            nanoTime = { time },
            startupTimeoutNanos = TimeUnit.MINUTES.toNanos(11),
        )
        val watchdog = LocalModelStreamWatchdog(progress, { cancels++ }, intervalMillis = 60_000L)
        try {
            time = TimeUnit.MINUTES.toNanos(10)
            assertFalse(watchdog.evaluate())
            progress.recordProgress()
            time += TimeUnit.MINUTES.toNanos(7)
            assertFalse(watchdog.evaluate())
            time += TimeUnit.MINUTES.toNanos(1)
            assertTrue(watchdog.evaluate())
            assertTrue(watchdog.timedOut())
            assertFalse(watchdog.evaluate())
            assertEquals(1, cancels)
        } finally { watchdog.close() }
    }

    @Test
    fun stoppingTheWatchdogCannotCancelARequest() {
        var time = 0L
        var cancels = 0
        val progress = LocalModelStreamProgressMonitor(
            timeoutNanos = TimeUnit.SECONDS.toNanos(1),
            nanoTime = { time },
        )
        val watchdog = LocalModelStreamWatchdog(progress, { cancels++ }, intervalMillis = 60_000L)
        watchdog.close()
        time = TimeUnit.SECONDS.toNanos(5)
        assertFalse(watchdog.evaluate())
        assertEquals(0, cancels)
    }
}
