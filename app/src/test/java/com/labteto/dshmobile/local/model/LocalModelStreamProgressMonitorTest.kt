package com.labteto.dshmobile.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelStreamProgressMonitorTest {
    @Test
    fun keepaliveDoesNotRestartUsefulProgressWindow() {
        var nanos = 0L
        val monitor = LocalModelStreamProgressMonitor(120L, nanoTime = { nanos })
        for (time in listOf(30L, 60L, 90L)) {
            nanos = time
            assertFalse(monitor.isStalled())
        }
        nanos = 120L
        assertTrue(monitor.isStalled())
    }

    @Test
    fun deepSeekQueueKeepaliveMayContinueBeforeGenerationButNotAfter() {
        var nanos = 0L
        val monitor = LocalModelStreamProgressMonitor(
            timeoutNanos = 8L * 60L,
            startupTimeoutNanos = 11L * 60L,
            nanoTime = { nanos },
        )
        nanos = 10L * 60L
        assertFalse(monitor.isStalled())
        nanos = 10L * 60L + 1
        monitor.recordProgress()
        nanos += 8L * 60L - 1
        assertFalse(monitor.isStalled())
        nanos += 1
        assertTrue(monitor.isStalled())
        val another = LocalModelStreamProgressMonitor(
            timeoutNanos = 8L * 60L,
            startupTimeoutNanos = 11L * 60L,
            nanoTime = { 11L * 60L },
        )
        assertFalse(another.isStalled())
    }

    @Test
    fun actualTextReasoningOrToolProgressResetsWindow() {
        var nanos = 0L
        val monitor = LocalModelStreamProgressMonitor(120L, nanoTime = { nanos })
        nanos = 100L
        monitor.recordProgress()
        nanos = 200L
        assertFalse(monitor.isStalled())
        nanos = 221L
        assertTrue(monitor.isStalled())
    }
}
