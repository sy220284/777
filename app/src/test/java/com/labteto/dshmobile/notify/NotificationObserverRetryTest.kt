package com.labteto.dshmobile.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationObserverRetryTest {
    @Test
    fun permanentFailureNeverRetries() {
        assertNull(
            notificationCollectorRetryDelay(
                retryable = false,
                failureCount = 1,
            ),
        )
    }

    @Test
    fun transientFailureUsesBoundedBackoffAndEventuallyStops() {
        assertEquals(250L, notificationCollectorRetryDelay(true, 1))
        assertEquals(4_000L, notificationCollectorRetryDelay(true, 5))
        assertNull(notificationCollectorRetryDelay(true, 6))
    }
}
