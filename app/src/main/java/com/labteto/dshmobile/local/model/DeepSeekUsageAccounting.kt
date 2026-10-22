package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.usage.ReportedTokenUsage
import com.labteto.dshmobile.local.usage.normalizedForAccounting

/**
 * Provider-specific field mapping only; shared Usage owns the arithmetic.
 * Preserve the old rule that a missing cache miss is inferred from input minus cache read.
 */
internal fun normalizeProviderTokenUsage(raw: DeepSeekTokenUsage): DeepSeekTokenUsage {
    val normalized = ReportedTokenUsage(
        inputTokens = raw.promptTokens,
        cacheReadTokens = raw.cacheHitTokens,
        cacheMissTokens = raw.cacheMissTokens,
        cacheWriteTokens = raw.cacheWriteTokens,
        outputTokens = raw.completionTokens,
        reasoningTokens = raw.reasoningTokens,
        reported = raw.reported,
    ).normalizedForAccounting(inferMissingCacheMiss = true)
    return raw.copy(
        promptTokens = normalized.inputTokens,
        cacheHitTokens = normalized.cacheReadTokens,
        cacheMissTokens = normalized.cacheMissTokens,
        cacheWriteTokens = normalized.cacheWriteTokens,
        completionTokens = normalized.outputTokens,
        reasoningTokens = normalized.reasoningTokens,
    )
}

internal fun DeepSeekTokenUsage.normalizedForAccounting(): DeepSeekTokenUsage =
    normalizeProviderTokenUsage(this)

/** Convert the old read-only preferences snapshot to a provider-neutral migration baseline. */
internal fun DeepSeekUsageSnapshot.toLegacyUsageCounters(): com.labteto.dshmobile.local.usage.LegacyUsageCounters =
    com.labteto.dshmobile.local.usage.LegacyUsageCounters(
        inputTokens = inputTokens,
        cacheHitTokens = cacheHitTokens,
        cacheMissTokens = cacheMissTokens,
        cacheWriteTokens = cacheWriteTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        requestCount = requestCount,
        unreportedRequestCount = unreportedRequestCount,
        estimatedCostCny = estimatedCostCny,
        unpricedTokens = unpricedTokens,
        updatedAt = updatedAt,
    )

/** Existing UI and runtime projections keep their DeepSeekUsageSnapshot type. */
internal fun com.labteto.dshmobile.local.usage.LegacyUsageCounters.asDeepSeekUsageSnapshot(): DeepSeekUsageSnapshot =
    DeepSeekUsageSnapshot(
        inputTokens = inputTokens,
        cacheHitTokens = cacheHitTokens,
        cacheMissTokens = cacheMissTokens,
        cacheWriteTokens = cacheWriteTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        requestCount = requestCount,
        unreportedRequestCount = unreportedRequestCount,
        estimatedCostCny = estimatedCostCny,
        unpricedTokens = unpricedTokens,
        updatedAt = updatedAt,
    )
