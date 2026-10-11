package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRequestAttemptPolicyTest {
    @Test
    fun rawTransportErrorIsRetryableOnlyBeforeAnyStreamOutput() {
        val error = IOException("transport disconnected")
        assertTrue(canRetryModelAttemptAfterOutput(error, 0))
        assertFalse(canRetryModelAttemptAfterOutput(error, 1))
    }

    @Test
    fun providerRetryFlagCannotOverrideEvidenceOfAlreadyEmittedOutput() {
        val transient = LocalModelException(
            code = "MODEL_HTTP_503",
            message = "temporarily unavailable",
            retryable = true,
        )
        assertTrue(canRetryModelAttemptAfterOutput(transient, 0))
        assertFalse(canRetryModelAttemptAfterOutput(transient, 512))
    }

    @Test
    fun knownAdmittedAndNonTransportFailuresNeverReplay() {
        val admitted = modelPostAdmissionFailure(
            code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
            detail = "connection closed",
        )
        assertFalse(canRetryModelAttemptAfterOutput(admitted, 0))
        assertFalse(canRetryModelAttemptAfterOutput(IllegalStateException("invalid model state"), 0))
    }
}
