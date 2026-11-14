package com.labteto.dshmobile.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNotificationPrivacyTest {
    @Test
    fun redactsCommonSecretLabelsAndNormalizesRows() {
        val sanitized = sanitizeNotificationField(
            "登录验证码：123456\n请勿泄露 password: hunter2",
            200,
        )

        assertTrue(sanitized.contains("验证码：[已脱敏]"))
        assertTrue(sanitized.contains("password：[已脱敏]"))
        assertFalse(sanitized.contains("123456"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("\n"))
    }

    @Test
    fun truncatesLongNotificationTextWithoutDroppingTheBoundarySignal() {
        val sanitized = sanitizeNotificationField("x".repeat(500), 32)

        assertEquals(33, sanitized.length)
        assertTrue(sanitized.endsWith("…"))
    }

    @Test
    fun notificationGrantAndConnectionAreIndependentlyReported() {
        assertTrue(notificationCapabilityStatus(false, false).contains("authorized=false\nconnected=false"))
        val disconnected = notificationCapabilityStatus(true, false)
        assertTrue(disconnected.contains("authorized=true\nconnected=false"))
        assertTrue(disconnected.contains("重新连接"))
        assertFalse(disconnected.contains("设置中授权"))
        assertEquals("authorized=true\nconnected=true", notificationCapabilityStatus(true, true))
    }

}
