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
            status = 503,
            admissionState = LocalModelAdmissionState.REJECTED,
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
    fun unclassifiedRetryableErrorWithoutHttpRejectionDoesNotProveSafety() {
        val unknown = LocalModelException(
            code = "MODEL_PROVIDER_UNCLASSIFIED", message = "may have started",
            retryable = true,
        )
        assertFalse(canRetryModelAttemptAfterOutput(unknown, 0))
        val legacyRejected = LocalModelException(
            code = "MODEL_HTTP_503", message = "explicit HTTP failure",
            retryable = true, status = 503,
        )
        assertTrue(canRetryModelAttemptAfterOutput(legacyRejected, 0))
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
    fun reasoningOnlyOutputBlocksRetryDespiteProviderRejectionFlag() {
        val retryableRejection = LocalModelException(
            code = "MODEL_HTTP_503", message = "rejected", retryable = true,
            status = 503, admissionState = LocalModelAdmissionState.REJECTED,
        )
        assertTrue(canRetryModelAttemptAfterOutput(retryableRejection, 0))
        assertFalse(canRetryModelAttemptAfterOutput(retryableRejection, 0, reasoningObserved = true))
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
