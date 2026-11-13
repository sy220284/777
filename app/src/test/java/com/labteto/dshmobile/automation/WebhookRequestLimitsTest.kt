package com.labteto.dshmobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebhookRequestLimitsTest {
    @Test fun validAndMalformedLengthsAreDistinct() {
        assertEquals(8, webhookBodyLength("8", 8))
        assertEquals(8, webhookBodyLength("0008", 8))
        assertEquals(0, webhookBodyLength(null, 8))
        assertNull(webhookBodyLength("-1", 8))
        assertNull(webhookBodyLength("invalid", 8))
    }
    @Test(expected = WebhookPayloadTooLarge::class)
    fun aboveLimitHasDedicatedFailure() { webhookBodyLength("9", 8) }
    @Test(expected = WebhookPayloadTooLarge::class)
    fun numericOverflowIsStillTooLarge() { webhookBodyLength("9".repeat(30), 8) }
}
