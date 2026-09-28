package com.labteto.dshmobile.local

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProcessExitStatusTest {
    @Test
    fun formatsExitEvidenceNeededForBackgroundTerminationDiagnosis() {
        val text = formatProcessExitStatus(
            reason = 13,
            status = 9,
            timestamp = 123456789L,
            importance = 300,
            processName = "com.sy220284.dshmobile",
            description = "MemoryLimiter:AnonSwap sample",
            pssKb = 12_345L,
            rssKb = 67_890L,
        )

        assertTrue(text.contains("系统原因码 13"))
        assertTrue(text.contains("状态码 9"))
        assertTrue(text.contains("MemoryLimiter:AnonSwap"))
        assertTrue(text.contains("PSS 12345 KiB"))
        assertTrue(text.contains("RSS 67890 KiB"))
    }
}
