package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Request admission is deliberately independent from provider-specific error codes.
 *
 * NOT_SENT means the complete request body was never handed to the transport and exact replay is
 * safe. MAYBE_ADMITTED begins once the complete body has been written: the provider may already be
 * doing inference even if no response headers arrive. ADMITTED begins only after a successful HTTP
 * response is accepted. REJECTED is an explicit non-success HTTP response.
 */
internal enum class LocalModelAdmissionState {
    NOT_APPLICABLE,
    NOT_SENT,
    REJECTED,
    MAYBE_ADMITTED,
    ADMITTED,
}

internal class LocalModelAdmissionTracker {
    @Volatile
    private var state: LocalModelAdmissionState = LocalModelAdmissionState.NOT_SENT

    fun markRequestBodySent() {
        if (state == LocalModelAdmissionState.NOT_SENT) {
            state = LocalModelAdmissionState.MAYBE_ADMITTED
        }
    }

    fun markAdmitted() {
        state = LocalModelAdmissionState.ADMITTED
    }

    fun snapshot(): LocalModelAdmissionState = state
}

internal fun RequestBody.withModelAdmissionTracking(
    tracker: LocalModelAdmissionTracker,
): RequestBody {
    val delegate = this
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isDuplex() = delegate.isDuplex()
        override fun isOneShot() = delegate.isOneShot()

        override fun writeTo(sink: BufferedSink) {
            delegate.writeTo(sink)
            // writeTo completed: the full JSON body has been handed to OkHttp's transport.
            tracker.markRequestBodySent()
        }
    }
}

internal fun modelTransportFailure(
    code: String,
    detail: String,
    tracker: LocalModelAdmissionTracker,
    requestId: String?,
    cause: Throwable,
): LocalModelException {
    val admission = tracker.snapshot()
    return if (admission == LocalModelAdmissionState.NOT_SENT) {
        LocalModelException(
            code = code,
            message = detail,
            retryable = true,
            cause = cause,
            requestId = requestId,
            admissionState = LocalModelAdmissionState.NOT_SENT,
        )
    } else {
        LocalModelException(
            code = code,
            message = "$detail。请求体已经发送，服务端是否开始推理无法确认；为避免重复推理或重复计费，777 不会自动重放同一请求。",
            retryable = false,
            cause = cause,
            requestId = requestId,
            providerCode = "request_maybe_admitted",
            admissionState = admission,
            continuationEligible = true,
        )
    }
}

/**
 * A successful HTTP admission means the provider may already have performed real inference.
 * Automatic whole-request replay is unsafe from this point because it can duplicate work, side
 * effects or billing. Provider-declared HTTP rejections keep their own retry policy.
 */
internal fun modelPostAdmissionFailure(
    code: String,
    detail: String,
    requestId: String? = null,
    cause: Throwable? = null,
): LocalModelException = LocalModelException(
    code = code,
    message = "$detail。模型服务已经接收本次请求，为避免重复推理、重复工具副作用或重复计费，777 不会自动重放整轮请求。",
    retryable = false,
    cause = cause,
    requestId = requestId,
    providerCode = "request_interrupted_after_admission",
    admissionState = LocalModelAdmissionState.ADMITTED,
    continuationEligible = true,
)

internal fun LocalModelException.withoutReplayAfterAdmission(): LocalModelException {
    if (!retryable && admissionState == LocalModelAdmissionState.ADMITTED) return this
    return LocalModelException(
        code = code,
        message = message.orEmpty(),
        retryable = false,
        cause = cause,
        status = status,
        providerRetryAfterMs = providerRetryAfterMs,
        requestId = requestId,
        providerCode = providerCode ?: "request_failed_after_admission",
        providerParam = providerParam,
        admissionState = LocalModelAdmissionState.ADMITTED,
        continuationEligible = true,
    )
}

/** Stable release-safe diagnostic category; never depends on R8 class names. */
internal fun modelFailureKind(error: LocalModelException): String = when {
    error.code == "WORK_BUDGET_EXHAUSTED" -> "local_budget"
    error.code == "MODEL_ROUTE_CIRCUIT_OPEN" -> "route_circuit_open"
    error.code == "MODEL_ROUTE_CIRCUIT_COOLDOWN" -> "route_circuit_cooldown"
    error.code in setOf("MODEL_CREDENTIAL_MISSING", "NO_MODEL_CREDENTIAL") -> "credential_missing"
    error.code.startsWith("CHATGPT_PLAN_") && (
        error.code.contains("LIMIT") ||
            error.code.contains("ELIGIBLE") ||
            error.code.contains("INVALID_USER")
        ) -> "account_limit"
    error.status == 401 || error.code.endsWith("_401") -> "auth_failed"
    error.status == 402 || error.code.endsWith("_402") -> "account_limit"
    error.status == 403 || error.code.endsWith("_403") -> "auth_forbidden"
    error.status == 429 || error.code.endsWith("_429") -> "rate_limited"
    error.admissionState == LocalModelAdmissionState.MAYBE_ADMITTED -> "request_maybe_admitted"
    error.admissionState == LocalModelAdmissionState.ADMITTED &&
        error.continuationEligible -> "stream_interrupted"
    error.code.contains("CONTEXT") -> "context_limit"
    error.code.contains("PROTOCOL") -> "protocol_error"
    error.code.contains("TIMEOUT") || error.cause is SocketTimeoutException -> "timeout"
    error.cause is UnknownHostException -> "dns_failure"
    error.cause is ConnectException -> "connect_failed"
    error.cause is SSLException -> "tls_failure"
    error.cause is EOFException -> "unexpected_eof"
    error.cause is SocketException -> "connection_reset"
    error.code.contains("NETWORK") -> "network_failure"
    error.code.startsWith("MODEL_HTTP_5") -> "provider_failure"
    else -> "model_failure"
}
