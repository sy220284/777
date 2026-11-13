package com.labteto.dshmobile.local

import org.junit.Assert.assertFalse
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
