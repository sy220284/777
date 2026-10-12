package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelRequestAttemptPolicyTest {
    @Test
    fun rawTransportErrorCannotProveRequestWasNotAdmitted() {
        val error = IOException("transport disconnected")
        assertFalse(canRetryModelAttemptAfterOutput(error, 0))
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
    fun maybeAdmittedCannotReplayEvenWhenProviderIncorrectlyCallsItRetryable() {
        val uncertain = LocalModelException(
            code = "MODEL_SOCKET_CLOSED", message = "uncertain after body send",
            retryable = true,
            admissionState = LocalModelAdmissionState.MAYBE_ADMITTED,
        )
        assertFalse(canRetryModelAttemptAfterOutput(uncertain, 0))
    }

    @Test
    fun explicitPreSendAndHttpRejectionRemainSafeToRetry() {
        val notSent = LocalModelException(
            code = "MODEL_CONNECT_FAILED", message = "not sent", retryable = true,
            admissionState = LocalModelAdmissionState.NOT_SENT,
        )
        val rejected = LocalModelException(
            code = "MODEL_HTTP_503", message = "rejected", retryable = true,
            admissionState = LocalModelAdmissionState.REJECTED,
        )
        assertTrue(canRetryModelAttemptAfterOutput(notSent, 0))
        assertTrue(canRetryModelAttemptAfterOutput(rejected, 0))
        assertFalse(canRetryModelAttemptAfterOutput(rejected, 30))
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
