package com.labteto.dshmobile.local.model

import java.io.IOException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    fun cancellableModelResponsePreservesNotSentCancellationState() = runBlocking {
        val tracker = LocalModelAdmissionTracker()
        val body = "{}".toRequestBody("application/json".toMediaType())
            .withModelAdmissionTracking(tracker)
        val client = OkHttpClient.Builder().addInterceptor {
            throw CancellationException("cancel before send")
        }.build()
        val request = Request.Builder().url("https://example.test").post(body).build()

        val failure = runCatching {
            withCancellableModelResponse(client.newCall(request), tracker) { error("unreachable") }
        }.exceptionOrNull()

        assertTrue(failure is LocalModelCancellationException)
        assertEquals(
            LocalModelAdmissionState.NOT_SENT,
            (failure as LocalModelCancellationException).admissionState,
        )
    }

    @Test
    fun cancellableModelResponsePreservesMaybeAdmittedCancellationState() = runBlocking {
        val tracker = LocalModelAdmissionTracker()
        val body = "{}".toRequestBody("application/json".toMediaType())
            .withModelAdmissionTracking(tracker)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().body?.writeTo(Buffer())
            throw CancellationException("cancel after send")
        }.build()
        val request = Request.Builder().url("https://example.test").post(body).build()

        val failure = runCatching {
            withCancellableModelResponse(client.newCall(request), tracker) { error("unreachable") }
        }.exceptionOrNull()

        assertTrue(failure is LocalModelCancellationException)
        assertEquals(
            LocalModelAdmissionState.MAYBE_ADMITTED,
            (failure as LocalModelCancellationException).admissionState,
        )
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

    @Test
    fun streamFailureKeepsNoReplayWhileExposingActualTransportCause() {
        val timeout = modelPostAdmissionFailure(
            "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", "stream idle",
            cause = SocketTimeoutException("idle timeout"),
        )
        assertEquals("stream_interrupted", modelFailureKind(timeout))
        assertEquals("timeout", modelTransportFailureKind(timeout))
        assertFalse(timeout.retryable)
        assertTrue(timeout.continuationEligible)

        val reset = modelPostAdmissionFailure(
            "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", "reset",
            cause = IOException("wrapper", SocketException("Connection reset")),
        )
        assertEquals("connection_reset", modelTransportFailureKind(reset))
        assertEquals("stream_interrupted", modelFailureKind(reset))
        assertEquals("broken_pipe", modelTransportFailureKind(modelPostAdmissionFailure(
            "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", "write failed",
            cause = SocketException("Broken pipe"),
        )))
        assertEquals("unexpected_eof", modelTransportFailureKind(modelPostAdmissionFailure(
            "MODEL_STREAM_INTERRUPTED_AFTER_ADMISSION", "early EOF",
            cause = IOException("wrapper", EOFException("EOF")),
        )))
    }

    @Test
    fun diagnosticsDifferentiateDnsTruncationAndNonTransportRejection() {
        val dns = modelTransportFailure(
            "MODEL_NETWORK", "dns failed", LocalModelAdmissionTracker(), "req-dns",
            UnknownHostException("example.test"),
        )
        assertEquals("dns_failure", modelTransportFailureKind(dns))
        assertTrue(dns.retryable)

        val incomplete = modelPostAdmissionFailure(
            "RESPONSES_STREAM_INTERRUPTED_AFTER_ADMISSION", "missing final marker",
        )
        assertEquals("stream_incomplete", modelTransportFailureKind(incomplete))
        assertFalse(incomplete.retryable)

        val rejected = com.labteto.dshmobile.local.LocalModelException(
            code = "MODEL_HTTP_429", message = "rate limit", retryable = true, status = 429,
        )
        assertEquals(null, modelTransportFailureKind(rejected))
    }
}
