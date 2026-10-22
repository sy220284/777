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
