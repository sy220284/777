package com.labteto.dshmobile.local

/** Shared web transport error contract; the provider no longer owns this type. */
class LocalWebException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception("[$code] $message", cause)
