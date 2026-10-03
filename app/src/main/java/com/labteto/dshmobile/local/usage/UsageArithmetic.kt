package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.DeepSeekTokenUsage
import com.labteto.dshmobile.local.TokenUsageRecord

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

internal fun saturatingUsageCostProduct(tokens: Long, rate: Double): Double {
    val safeTokens = tokens.coerceAtLeast(0L).toDouble()
    val safeRate = rate.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val value = safeTokens * safeRate
    return if (value.isFinite() && value >= 0.0) value else Double.MAX_VALUE
}

internal fun DeepSeekTokenUsage.normalizedForAccounting(): DeepSeekTokenUsage {
    val prompt = promptTokens.coerceAtLeast(0L)
    val hit = cacheHitTokens.coerceAtLeast(0L).coerceAtMost(prompt)
    val miss = cacheMissTokens.coerceAtLeast(0L)
        .takeIf { it > 0L }
        ?: nonNegativeUsageDifference(prompt, hit)
    val write = cacheWriteTokens.coerceAtLeast(0L).coerceAtMost(miss)
    return copy(
        promptTokens = prompt,
        cacheHitTokens = hit,
        cacheMissTokens = miss,
        cacheWriteTokens = write,
        completionTokens = completionTokens.coerceAtLeast(0L),
        reasoningTokens = reasoningTokens.coerceAtLeast(0L),
    )
}

internal fun TokenUsageRecord.normalizedForAccounting(): TokenUsageRecord = copy(
    inputTokens = inputTokens.coerceAtLeast(0L),
    cacheHitTokens = cacheHitTokens.coerceAtLeast(0L),
    cacheMissTokens = cacheMissTokens.coerceAtLeast(0L),
    cacheWriteTokens = cacheWriteTokens.coerceAtLeast(0L)
        .coerceAtMost(cacheMissTokens.coerceAtLeast(0L)),
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

/** Diagnostic labels cannot turn an otherwise valid API accounting record into an oversized row. */
internal fun TokenUsageRecord.boundedForStorage(): TokenUsageRecord = normalizedForAccounting().copy(
    model = model.take(128),
    context = context.copy(
        sessionTitle = context.sessionTitle?.take(256),
        taskLabel = context.taskLabel?.take(256),
        runKind = context.runKind?.take(64),
    ),
    route = route?.copy(
        profileId = route.profileId?.take(128),
        provider = route.provider.take(80),
        model = route.model.take(128),
        baseUrl = route.baseUrl.take(512),
        authKind = route.authKind.take(64),
        protocol = route.protocol.take(64),
        fingerprint = route.fingerprint.take(128),
    ),
)
