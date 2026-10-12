package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.io.IOException

/**
 * Model retries must be exact replays of an attempt that has not produced any output.
 *
 * Providers normally wrap admitted transport failures in LocalModelException with retryable=false.
 * A raw IOException can still escape from a custom/compatible transport after text chunks were
 * delivered. In that case the response itself proves that the request reached the provider:
 * never replay it automatically. Continue the task using the durable Work recovery path instead.
 */
internal fun canRetryModelAttemptAfterOutput(
    error: Exception,
    streamedChars: Int,
): Boolean {
    if (streamedChars > 0) return false
    return when (error) {
        is LocalModelException -> error.retryable
        is IOException -> true
        else -> false
    }
}
