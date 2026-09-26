package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelErrorTest {
    @Test
    fun recognizesProviderContextOverflow() {
        assertTrue(
            contextWindowExceeded(
                LocalModelException(
                    code = "MODEL_HTTP_400",
                    message = "maximum context length exceeded",
                    retryable = false,
                ),
            ),
        )
        assertTrue(
            contextWindowExceeded(
                LocalModelException(
                    code = "MODEL_HTTP_422",
                    message = "输入过长，超过上下文窗口",
                    retryable = false,
                ),
            ),
        )
    }

    @Test
    fun parsesProviderRetryAfterSecondsAndDate() {
        val client = DeepSeekClient(
            okhttp3.OkHttpClient(),
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
        )
        assertEquals(7_000L, client.parseRetryAfterMillis("7", nowMillis = 0L))
        assertEquals(
            60_000L,
            client.parseRetryAfterMillis(
                "Thu, 01 Jan 1970 00:01:00 GMT",
                nowMillis = 0L,
            ),
        )
        assertNull(client.parseRetryAfterMillis("n/a", nowMillis = 0L))
    }

    @Test
    fun doesNotMisclassifyOrdinaryBadRequest() {
        assertFalse(
            contextWindowExceeded(
                LocalModelException(
                    code = "MODEL_HTTP_400",
                    message = "invalid tool schema",
                    retryable = false,
                ),
            ),
        )
        assertFalse(
            contextWindowExceeded(
                LocalModelException(
                    code = "MODEL_HTTP_500",
                    message = "context window unavailable",
                    retryable = true,
                ),
            ),
        )
    }
}
