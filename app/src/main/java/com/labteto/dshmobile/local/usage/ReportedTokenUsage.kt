package com.labteto.dshmobile.local.usage

/**
 * One provider-reported request usage value before persistence.
 * This is a value contract, not a second ledger or model-specific pricing policy.
 */
internal data class ReportedTokenUsage(
    val inputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val reported: Boolean = false,
)

/**
 * Provider parser may leave misses absent; only the model adapter chooses inference.
 * Stored accounting records must never infer misses from a zero field retroactively.
 */
internal fun ReportedTokenUsage.normalizedForAccounting(
    inferMissingCacheMiss: Boolean = false,
): ReportedTokenUsage {
    val input = inputTokens.coerceAtLeast(0L)
    val read = cacheReadTokens.coerceIn(0L, input)
    val remaining = nonNegativeUsageDifference(input, read)
    val reportedMiss = cacheMissTokens.coerceAtLeast(0L)
    val miss = (if (inferMissingCacheMiss && reportedMiss == 0L) remaining else reportedMiss)
        .coerceAtMost(remaining)
    return copy(
        inputTokens = input,
        cacheReadTokens = read,
        cacheMissTokens = miss,
        cacheWriteTokens = cacheWriteTokens.coerceIn(0L, miss),
        outputTokens = outputTokens.coerceAtLeast(0L),
        reasoningTokens = reasoningTokens.coerceAtLeast(0L),
    )
}
