package com.labteto.dshmobile.local.chat

import kotlinx.serialization.json.JsonObject

internal fun sanitizeChatContinuity(
    value: ChatContinuityState,
    previous: ChatContinuityState,
    raw: JsonObject,
): ChatContinuityState = ChatContinuityState(
    recentEvents = if (raw.containsKey("recentEvents")) {
        sanitizeContinuityStrings(value.recentEvents, limit = 5, maxChars = 180)
    } else previous.recentEvents.takeLast(5),
    recurringEvents = emptyList(),
    decisions = if (raw.containsKey("decisions")) {
        sanitizeContinuityStrings(value.decisions, limit = 4, maxChars = 180)
    } else previous.decisions.takeLast(4),
    unfinished = if (raw.containsKey("unfinished")) {
        sanitizeContinuityStrings(value.unfinished, limit = 4, maxChars = 180)
    } else previous.unfinished.takeLast(4),
    // Provenance is derived from durable Pending turns after parsing; the model never owns it.
    evidence = previous.evidence,
)

private fun sanitizeContinuityStrings(
    values: List<String>,
    limit: Int,
    maxChars: Int,
): List<String> = values.asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { it.take(maxChars) }
    .distinctBy(::normalizeChatInteractionText)
    .toList()
    .takeLast(limit)
