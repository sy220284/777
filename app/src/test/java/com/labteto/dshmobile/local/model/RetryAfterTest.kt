package com.labteto.dshmobile.local.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryAfterTest {
    @Test fun secondsSaturateWithoutWrappingAndMalformedValuesAreAbsent() {
        assertEquals(2000L, retryAfterMillis(" 2 "))
        assertEquals(0L, retryAfterMillis("-2"))
        assertEquals(Long.MAX_VALUE, retryAfterMillis("9223372036854776"))
        assertEquals(Long.MAX_VALUE, retryAfterMillis("999999999999999999999999999"))
        assertNull(retryAfterMillis(null))
        assertNull(retryAfterMillis(""))
        assertNull(retryAfterMillis("invalid"))
        assertNull(retryAfterMillis("1.5"))
    }
    @Test fun httpDateUsesClockAndPastDatesWaitZero() {
        val date = "Thu, 01 Jan 1970 00:00:01 GMT"
        assertEquals(1000L, retryAfterMillis(date, 0L))
        assertEquals(0L, retryAfterMillis(date, 2000L))
        assertEquals(Long.MAX_VALUE, retryAfterMillis(date, Long.MIN_VALUE))
    }
}
