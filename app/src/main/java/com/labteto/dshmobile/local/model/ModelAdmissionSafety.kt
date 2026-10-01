package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException

/**
 * A successful HTTP admission means the provider may already have performed real inference.
 * Automatic whole-request replay is unsafe from this point because it can duplicate work, side
 * effects or billing. Provider-declared pre-admission HTTP failures keep their own retry policy.
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
)

internal fun LocalModelException.withoutReplayAfterAdmission(): LocalModelException {
    if (!retryable) return this
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
    )
}
