package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.TokenUsageAggregate
import kotlinx.serialization.Serializable

/** Last pre-SQLite usage snapshot, converted at the provider boundary. */
@Serializable
internal data class LegacyUsageCounters(
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
)

/**
 * A single DB-owned migration offset. It is the non-negative difference between the
 * previous persisted counters and the SQLite lifetime totals at the migration fence.
 * Future SQLite requests add to lifetime totals, never to this offset.
 */
internal fun legacyUsageOffset(
    legacy: LegacyUsageCounters,
    current: TokenUsageAggregate,
): LegacyUsageCounters {
    // Old SharedPreferences and SQLite can cover overlapping but non-identical requests.
    // The SQLite input/cache counters are already committed facts. Only newly recovered
    // input tokens can carry additional cache categories; otherwise fieldwise maxima
    // could produce more cache-hit + cache-miss tokens than total input tokens.
    val missingInput = nonNegativeUsageDifference(legacy.inputTokens, current.inputTokens)
    val missingCacheHit = minOf(
        nonNegativeUsageDifference(legacy.cacheHitTokens, current.cacheHitTokens),
        missingInput,
    )
    val remainingInput = nonNegativeUsageDifference(missingInput, missingCacheHit)
    val missingCacheMiss = minOf(
        nonNegativeUsageDifference(legacy.cacheMissTokens, current.cacheMissTokens),
        remainingInput,
    )
    val missingCacheWrite = minOf(
        nonNegativeUsageDifference(legacy.cacheWriteTokens, current.cacheWriteTokens),
        missingCacheMiss,
    )
    return LegacyUsageCounters(
        inputTokens = missingInput,
        cacheHitTokens = missingCacheHit,
        cacheMissTokens = missingCacheMiss,
        cacheWriteTokens = missingCacheWrite,
        outputTokens = nonNegativeUsageDifference(legacy.outputTokens, current.outputTokens),
        reasoningTokens = nonNegativeUsageDifference(legacy.reasoningTokens, current.reasoningTokens),
        requestCount = nonNegativeUsageDifference(legacy.requestCount, current.requestCount),
        unreportedRequestCount = nonNegativeUsageDifference(legacy.unreportedRequestCount, current.unreportedRequestCount),
        estimatedCostCny = (legacy.estimatedCostCny.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0).let { old ->
            (old - current.estimatedCostCny.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        },
    )
}

/** Read-only compatibility projection, not a second persisted usage account. */
internal fun lifetimeUsageProjection(
    tracked: TokenUsageAggregate,
    offset: LegacyUsageCounters,
    unpricedTokens: Long,
    updatedAt: Long,
): LegacyUsageCounters = LegacyUsageCounters(
    inputTokens = saturatingUsageAdd(tracked.inputTokens, offset.inputTokens),
    cacheHitTokens = saturatingUsageAdd(tracked.cacheHitTokens, offset.cacheHitTokens),
    cacheMissTokens = saturatingUsageAdd(tracked.cacheMissTokens, offset.cacheMissTokens),
    cacheWriteTokens = saturatingUsageAdd(tracked.cacheWriteTokens, offset.cacheWriteTokens),
    outputTokens = saturatingUsageAdd(tracked.outputTokens, offset.outputTokens),
    reasoningTokens = saturatingUsageAdd(tracked.reasoningTokens, offset.reasoningTokens),
    requestCount = saturatingUsageAdd(tracked.requestCount, offset.requestCount),
    unreportedRequestCount = saturatingUsageAdd(tracked.unreportedRequestCount, offset.unreportedRequestCount),
    estimatedCostCny = saturatingUsageCostAdd(tracked.estimatedCostCny, offset.estimatedCostCny),
    unpricedTokens = unpricedTokens.coerceAtLeast(0L),
    updatedAt = updatedAt.coerceAtLeast(0L),
)

@Serializable
internal data class LegacyUsageBridge(
    val offset: LegacyUsageCounters = LegacyUsageCounters(),
    val unpricedTokens: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * The historical gap is unattributable to model, Chat/Work mode or request ID.
 * Project it into global lifetime totals only; never invent provider/session attribution.
 */
internal fun TokenUsageAggregate.withLegacyUnattributedGap(offset: LegacyUsageCounters): TokenUsageAggregate = copy(
    inputTokens = saturatingUsageAdd(inputTokens, offset.inputTokens),
    cacheHitTokens = saturatingUsageAdd(cacheHitTokens, offset.cacheHitTokens),
    cacheMissTokens = saturatingUsageAdd(cacheMissTokens, offset.cacheMissTokens),
    cacheWriteTokens = saturatingUsageAdd(cacheWriteTokens, offset.cacheWriteTokens),
    outputTokens = saturatingUsageAdd(outputTokens, offset.outputTokens),
    reasoningTokens = saturatingUsageAdd(reasoningTokens, offset.reasoningTokens),
    requestCount = saturatingUsageAdd(requestCount, offset.requestCount),
    unreportedRequestCount = saturatingUsageAdd(unreportedRequestCount, offset.unreportedRequestCount),
    estimatedCostCny = saturatingUsageCostAdd(estimatedCostCny, offset.estimatedCostCny),
)

internal fun LegacyUsageCounters.hasUnattributedGap(): Boolean =
    inputTokens > 0L || cacheHitTokens > 0L || cacheMissTokens > 0L ||
        cacheWriteTokens > 0L || outputTokens > 0L || reasoningTokens > 0L ||
        requestCount > 0L || unreportedRequestCount > 0L || estimatedCostCny > 0.0
