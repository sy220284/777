package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelFailureOrigin
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import java.net.SocketException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWorkTransportRecoveryTest {
    @Test fun lossBeforeRequestSendDoesNotExhaustWorkTask() {
        val error = LocalModelException(
            "MODEL_NETWORK", "offline", retryable = true,
            admissionState = LocalModelAdmissionState.NOT_SENT,
            origin = LocalModelFailureOrigin.TRANSPORT,
        )
        assertTrue(isWorkTransportInterruption(error))
        assertTrue(shouldAutoContinueWorkFailure(error, automaticContinuationCount = 100, pendingInputs = 0))
        assertFalse(shouldAutoContinueWorkFailure(error, automaticContinuationCount = 100, pendingInputs = 1))
    }

    @Test fun admittedStreamFailureContinuesFromCheckpointWithoutReplayingPost() {
        val error = LocalModelException(
            "RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION", "connection reset",
            retryable = false, cause = SocketException("Connection reset"),
            admissionState = LocalModelAdmissionState.ADMITTED,
            continuationEligible = true,
            origin = LocalModelFailureOrigin.STREAM_PARSE,
        )
        assertTrue(isWorkTransportInterruption(error))
        assertTrue(shouldAutoContinueWorkFailure(error, automaticContinuationCount = 50, pendingInputs = 0))
        assertTrue(isWorkTransportContinuationCode(error.code))
    }

    @Test fun persistentTransportFailureUsesBoundedBackoff() {
        assertEquals(2_000L, workTransportResumeBackoffMillis(1))
        assertEquals(4_000L, workTransportResumeBackoffMillis(2))
        assertEquals(8_000L, workTransportResumeBackoffMillis(3))
        assertEquals(120_000L, workTransportResumeBackoffMillis(20))
        assertEquals(120_000L, workTransportResumeBackoffMillis(Int.MAX_VALUE))
    }

    @Test fun authProviderRejectionAndNonTransportErrorsDoNotLoop() {
        val unauthorized = LocalModelException(
            "MODEL_HTTP_401", "invalid token", retryable = false, status = 401,
            admissionState = LocalModelAdmissionState.REJECTED,
            origin = LocalModelFailureOrigin.PROVIDER_REJECTED,
        )
        assertFalse(isWorkTransportInterruption(unauthorized))
        assertFalse(shouldAutoContinueWorkFailure(unauthorized, 100, 0))
        val semantic = LocalModelException(
            "WORK_BUDGET_EXHAUSTED", "slice budget", retryable = false,
            continuationEligible = true,
        )
        assertTrue(shouldAutoContinueWorkFailure(semantic, 0, 0))
        assertFalse(shouldAutoContinueWorkFailure(semantic, 2, 0))
        assertFalse(isWorkTransportContinuationCode(unauthorized.code))
    }
}
