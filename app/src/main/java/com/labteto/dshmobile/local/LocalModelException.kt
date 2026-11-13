package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAdmissionState

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
) : Exception(message, cause)
