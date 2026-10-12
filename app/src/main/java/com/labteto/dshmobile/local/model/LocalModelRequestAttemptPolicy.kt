package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import java.io.IOException

/**
 * Whole-request replay is permitted only with affirmative evidence that inference did not
 * start: a pre-send failure, or a provider's explicit HTTP rejection marked retryable.
 *
 * A raw IOException gives NO such proof even when the client saw zero response characters.
 * In particular, the service could have finished inference and the response could have been
 * lost in transit. Replaying such an outcome can duplicate usage on subscription models.
 */
internal fun canRetryModelAttemptAfterOutput(
    error: Exception,
    streamedChars: Int,
): Boolean {
    if (streamedChars > 0) return false
    return when (error) {
        is LocalModelException -> error.retryable && when (error.admissionState) {
            LocalModelAdmissionState.NOT_SENT,
            LocalModelAdmissionState.REJECTED -> true
            // A provider may expose an explicit failed HTTP status before adopting the newer
            // admission API; an actual non-success response is affirmative rejection evidence.
            LocalModelAdmissionState.NOT_APPLICABLE ->
                error.status?.let { it in 400..599 } == true
            LocalModelAdmissionState.MAYBE_ADMITTED,
            LocalModelAdmissionState.ADMITTED -> false
        }
        is IOException -> false // No admission evidence: fail conservatively; do not guess.
        else -> false
    }
}
