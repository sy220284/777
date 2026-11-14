package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAdmissionState

/** Origin is assigned by the actual failing layer and remains stable under R8. */
internal enum class LocalModelFailureOrigin(val wireValue: String) {
    LOCAL_PREFLIGHT("local_preflight"),
    PROTOCOL_ENCODE("protocol_encode"),
    PROVIDER_REJECTED("provider_rejected"),
    TRANSPORT("transport"),
    STREAM_PARSE("stream_parse"),
}

/** Stable shared model error contract; preserve original JVM package and retry metadata. */
internal class LocalModelException(
    val code: String,
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val status: Int? = null,
    val providerRetryAfterMs: Long? = null,
    val requestId: String? = null,
    val providerCode: String? = null,
    val providerParam: String? = null,
    val admissionState: LocalModelAdmissionState = LocalModelAdmissionState.NOT_APPLICABLE,
    val continuationEligible: Boolean = false,
    val origin: LocalModelFailureOrigin? = null,
) : Exception(message, cause)
