package com.labteto.dshmobile.local.model

/**
 * An attempt's observable output is evidence, not a durable assistant reply.
 * Scoped to one provider attempt; reset only when a new attempt actually starts.
 */
internal class LocalModelAttemptStreamEvidence(
    private val maxTailChars: Int = 4_096,
) {
    var emittedChars: Int = 0
        private set
    var tail: String = ""
        private set

    val truncated: Boolean get() = emittedChars > tail.length

    fun beginAttempt() {
        emittedChars = 0
        tail = ""
    }

    fun append(delta: String) {
        if (delta.isEmpty()) return
        emittedChars = (emittedChars.toLong() + delta.length).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        tail = takeLastWithoutSplittingSurrogatePair(tail + delta, maxTailChars)
    }

    fun retryable(error: Exception): Boolean = canRetryModelAttemptAfterOutput(error, emittedChars)
}
