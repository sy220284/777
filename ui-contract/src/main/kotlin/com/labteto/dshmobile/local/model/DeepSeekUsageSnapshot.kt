package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.usage.saturatingUsageAdd

data class DeepSeekUsageSnapshot(
    val inputTokens: Long = 0L,
    val cacheHitTokens: Long = 0L,
    val cacheMissTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val reasoningTokens: Long = 0L,
    val requestCount: Long = 0L,
    val unreportedRequestCount: Long = 0L,
    val estimatedCostCny: Double = 0.0,
    val unpricedTokens: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val totalTokens: Long get() = saturatingUsageAdd(inputTokens, outputTokens)
    val totalRequestCount: Long get() = saturatingUsageAdd(requestCount, unreportedRequestCount)
    val cacheMeasuredTokens: Long get() = saturatingUsageAdd(cacheHitTokens, cacheMissTokens)
    val cacheHitRate: Double
        get() = if (cacheMeasuredTokens <= 0L) 0.0 else cacheHitTokens.toDouble() / cacheMeasuredTokens.toDouble()
}
