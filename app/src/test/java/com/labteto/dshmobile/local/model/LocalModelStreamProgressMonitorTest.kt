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
