package com.labteto.dshmobile.ui.screens.local

import android.os.Build
import com.labteto.dshmobile.local.runtime.formatProcessExitStatus
import com.labteto.dshmobile.local.runtime.isAndroid17MemoryLimiterExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs on both supported Android system images, not only the JVM mock environment. */
class AndroidDualVersionRuntimeCompatibilityTest {
    @Test
    fun inlineSpeechUsesAndroid17OnlyBackgroundPolicy() {
        val api = Build.VERSION.SDK_INT
        assertTrue("Dual-version device job requires API 36 or newer", api >= 36)
        assertEquals(api >= 37, shouldSuspendInlineSpeechOnBackground(api))
        assertFalse(shouldSuspendInlineSpeechOnBackground(36))
        assertTrue(shouldSuspendInlineSpeechOnBackground(37))
    }

    @Test
    fun memoryLimiterEvidenceDoesNotOverwriteLegacyExitStatus() {
        val description = "MemoryLimiter:AnonSwap background process"
        assertTrue(isAndroid17MemoryLimiterExit(description))
        val report = formatProcessExitStatus(
            reason = 13, status = 9, timestamp = 1234L, importance = 300,
            processName = "com.sy220284.dshmobile", description = description,
            pssKb = 12_345L, rssKb = 67_890L,
        )
        assertTrue(report.contains("系统其他原因（需关注）"))
        assertTrue(report.contains("Android 17 内存限制触发"))
        assertTrue(report.contains("系统原因码 13"))
        assertFalse(isAndroid17MemoryLimiterExit("ordinary Android 16 app exit"))
    }
}
