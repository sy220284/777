package com.labteto.dshmobile.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdentityTest {
    @Test
    fun javaHashCollisionKeysStillProduceDifferentNotificationIds() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            stableNotificationId("session", "Aa"),
            stableNotificationId("session", "BB"),
        )
    }

    @Test
    fun namespacesSeparateTheSameLogicalKey() {
        val key = "same-session"
        assertNotEquals(
            stableNotificationId("remote:completions", key),
            stableNotificationId("automation", key),
        )
    }

    @Test
    fun idsAreStableAndPositiveAcrossRepeatedCalls() {
        val first = stableNotificationId("local-execution", "session-1")
        repeat(1_000) {
            assertEquals(first, stableNotificationId("local-execution", "session-1"))
        }
        assertTrue(first > 0)
    }
}
