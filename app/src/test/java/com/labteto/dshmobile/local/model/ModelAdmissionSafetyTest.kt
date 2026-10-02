package com.labteto.dshmobile.local.model

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdmissionSafetyTest {
    @Test
    fun completeRequestBodyMovesAttemptIntoMaybeAdmittedState() {
        val tracker = LocalModelAdmissionTracker()
        val body = "{}".toRequestBody("application/json".toMediaType())
            .withModelAdmissionTracking(tracker)

        assertEquals(LocalModelAdmissionState.NOT_SENT, tracker.snapshot())
        body.writeTo(Buffer())

        assertEquals(LocalModelAdmissionState.MAYBE_ADMITTED, tracker.snapshot())
    }

    @Test
    fun transportFailureBeforeBodyWriteCanRetryExactly() {
        val tracker = LocalModelAdmissionTracker()

        val error = modelTransportFailure(
            code = "MODEL_NETWORK",
            detail = "connect failed",
            tracker = tracker,
            requestId = "request-1",
            cause = IOException("connect failed"),
        )

        assertTrue(error.retryable)
        assertFalse(error.continuationEligible)
        assertEquals(LocalModelAdmissionState.NOT_SENT, error.admissionState)
    }

    @Test
    fun transportFailureAfterBodyWriteCannotReplayButCanContinueTask() {
        val tracker = LocalModelAdmissionTracker()
        "{}".toRequestBody("application/json".toMediaType())
            .withModelAdmissionTracking(tracker)
            .writeTo(Buffer())

        val error = modelTransportFailure(
            code = "MODEL_NETWORK",
            detail = "connection reset",
            tracker = tracker,
            requestId = "request-2",
            cause = IOException("reset"),
        )

        assertFalse(error.retryable)
        assertTrue(error.continuationEligible)
        assertEquals(LocalModelAdmissionState.MAYBE_ADMITTED, error.admissionState)
        assertEquals("request_maybe_admitted", error.providerCode)
    }

    @Test
    fun admittedStreamInterruptionIsContinuationSafeButNotReplayable() {
        val error = modelPostAdmissionFailure(
            code = "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION",
            detail = "stream closed",
            requestId = "request-3",
        )

        assertFalse(error.retryable)
        assertTrue(error.continuationEligible)
        assertEquals(LocalModelAdmissionState.ADMITTED, error.admissionState)
        assertEquals("stream_interrupted", modelFailureKind(error))
    }
}
