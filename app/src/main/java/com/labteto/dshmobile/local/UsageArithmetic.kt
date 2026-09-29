package com.labteto.dshmobile.local

internal fun saturatingUsageAdd(left: Long, right: Long): Long {
    val safeLeft = left.coerceAtLeast(0L)
    val safeRight = right.coerceAtLeast(0L)
    return if (safeLeft > Long.MAX_VALUE - safeRight) Long.MAX_VALUE else safeLeft + safeRight
}

internal fun nonNegativeUsageDifference(total: Long, part: Long): Long {
    val safeTotal = total.coerceAtLeast(0L)
    val safePart = part.coerceAtLeast(0L)
    return if (safeTotal <= safePart) 0L else safeTotal - safePart
}

internal fun saturatingUsageCostAdd(left: Double, right: Double): Double {
    val safeLeft = left.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val safeRight = right.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    return if (safeLeft > Double.MAX_VALUE - safeRight) Double.MAX_VALUE else safeLeft + safeRight
}

internal fun TokenUsageRecord.normalizedForAccounting(): TokenUsageRecord = copy(
    inputTokens = inputTokens.coerceAtLeast(0L),
    cacheHitTokens = cacheHitTokens.coerceAtLeast(0L),
    cacheMissTokens = cacheMissTokens.coerceAtLeast(0L),
    outputTokens = outputTokens.coerceAtLeast(0L),
    reasoningTokens = reasoningTokens.coerceAtLeast(0L),
    estimatedCostCny = estimatedCostCny.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
)

internal fun Sequence<TokenUsageRecord>.distinctForAccounting(): Sequence<TokenUsageRecord> = sequence {
    val seenRequestIds = HashSet<String>()
    for (raw in this@distinctForAccounting) {
        val record = raw.normalizedForAccounting()
        val id = record.requestId
        if (id.isNotBlank() && !seenRequestIds.add(id)) continue
        yield(record)
    }
}
