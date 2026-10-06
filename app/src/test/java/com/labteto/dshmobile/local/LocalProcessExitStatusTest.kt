package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.runtime.formatProcessExitStatus
import com.labteto.dshmobile.local.runtime.isExpectedProcessExit
import com.labteto.dshmobile.local.runtime.processExitReasonLabel
import org.junit.Assert.assertFalse
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

        assertTrue(text.contains("系统其他原因（需关注）"))
        assertTrue(text.contains("系统原因码 13"))
        assertTrue(text.contains("状态码 9"))
        assertTrue(text.contains("MemoryLimiter:AnonSwap"))
        assertTrue(text.contains("PSS 12345 KiB"))
        assertTrue(text.contains("RSS 67890 KiB"))
    }

    @Test
    fun labelsPackageUpdateAsExpectedInsteadOfCrash() {
        val text = formatProcessExitStatus(
            reason = 16,
            status = 0,
            timestamp = 123L,
            importance = 400,
            processName = "com.sy220284.dshmobile",
            description = "stop com.sy220284.dshmobile due to installPackageLI",
            pssKb = 0,
            rssKb = 1,
        )

        assertTrue(text.contains("应用更新（正常/预期）"))
        assertTrue(isExpectedProcessExit(16))
        assertFalse(isExpectedProcessExit(4))
        assertTrue(processExitReasonLabel(4).contains("崩溃"))
    }

    @Test
    fun removesAppPrivatePathFromExitDescription() {
        val text = formatProcessExitStatus(
            reason = 4,
            status = 1,
            timestamp = 123L,
            importance = 300,
            processName = "com.sy220284.dshmobile",
            description = "crash at /data/user/0/com.sy220284.dshmobile/files/private.txt",
            pssKb = 10,
            rssKb = 20,
        )

        assertTrue(text.contains("<app-private-path>"))
        assertFalse(text.contains("/data/user/0/"))
    }
}
