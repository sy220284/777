package com.labteto.dshmobile.local.usage

import com.labteto.dshmobile.local.TokenUsageRecord


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

internal fun TokenUsageRecord.normalizedForAccounting(): TokenUsageRecord {
    val prompt = inputTokens.coerceAtLeast(0L)
    val hit = cacheHitTokens.coerceAtLeast(0L).coerceAtMost(prompt)
    // Cache hits and misses partition the reported prompt, rather than creating
    // additional billable input tokens when providers return contradictory fields.
    val miss = cacheMissTokens.coerceAtLeast(0L)
        .coerceAtMost(nonNegativeUsageDifference(prompt, hit))
    return copy(
        inputTokens = prompt,
        cacheHitTokens = hit,
        cacheMissTokens = miss,
        cacheWriteTokens = cacheWriteTokens.coerceAtLeast(0L).coerceAtMost(miss),
        outputTokens = outputTokens.coerceAtLeast(0L),
        reasoningTokens = reasoningTokens.coerceAtLeast(0L),
        estimatedCostCny = estimatedCostCny.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
    )
}

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
    route = route?.let { value -> value.copy(
        profileId = value.profileId?.take(128),
        provider = value.provider.take(80),
        model = value.model.take(128),
        baseUrl = value.baseUrl.take(512),
        authKind = value.authKind.take(64),
        protocol = value.protocol.take(64),
        fingerprint = value.fingerprint.take(128),
    ) },
)
