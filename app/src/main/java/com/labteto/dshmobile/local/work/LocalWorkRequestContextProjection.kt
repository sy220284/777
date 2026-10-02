package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Derives a bounded Work-only request surface from the durable model history.
 *
 * The durable/session history remains the source of truth. Older semantic history is represented by
 * the existing trusted work checkpoint while a recent verbatim tail stays available to the model.
 * This mirrors the "full facts + bounded model projection" split used by the reference Harnesses.
 */
internal data class LocalWorkRequestProjection(
    val messages: List<JsonObject>,
    val projected: Boolean,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val omittedMessages: Int = 0,
)


internal fun workSteadyStateHistoryBudget(
    base: LocalHistoryBudget,
    currentHistoryTokens: Int,
    extraTokens: Int = 0,
): LocalHistoryBudget {
    val operationalLimit = base.maxHistoryTokens ?: return base
    val totalRequestTokens = currentHistoryTokens.toLong() + extraTokens.coerceAtLeast(0).toLong()
    if (totalRequestTokens <= workRequestProjectionTriggerTokens(operationalLimit).toLong()) {
        return base
    }
    val target = workRequestProjectionTargetTokens(operationalLimit)
    return base.copy(
        maxHistoryTokens = target,
        tailTokens = minOf(
            base.tailTokens ?: WORK_REQUEST_TAIL_TOKENS,
            WORK_REQUEST_TAIL_TOKENS,
            (target * 0.38).toInt().coerceAtLeast(1_024),
        ),
        maxSummaryChars = minOf(base.maxSummaryChars, WORK_REQUEST_SUMMARY_CHARS),
        maxToolResultChars = minOf(base.maxToolResultChars, WORK_REQUEST_TOOL_RESULT_CHARS),
        maxToolResultTokens = minOf(base.maxToolResultTokens, WORK_REQUEST_TOOL_RESULT_TOKENS),
    )
}

internal fun projectWorkRequestContext(
    messages: List<JsonObject>,
    tools: JsonArray,
    compactor: LocalHistoryCompactor,
    operationalLimitTokens: Int,
    measuredPressure: LocalPromptPressure? = null,
): LocalWorkRequestProjection {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    val beforePressure = measuredPressure ?: LocalPromptPressureMeter.measure(
        messages = messages,
        tools = tools,
        operationalLimitTokens = limit,
    )
    val trigger = workRequestProjectionTriggerTokens(limit)
    if (beforePressure.estimatedInputTokens <= trigger) {
        return LocalWorkRequestProjection(
            messages = messages,
            projected = false,
            estimatedTokensBefore = beforePressure.estimatedInputTokens,
            estimatedTokensAfter = beforePressure.estimatedInputTokens,
        )
    }

    val target = workRequestProjectionTargetTokens(limit)
    val leadingSystemCount = messages.takeWhile { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "system"
    }.size
    val protectedHead = messages.take(leadingSystemCount)
    val compactableMessages = if (leadingSystemCount > 1) {
        listOf(protectedHead.first()) + messages.drop(leadingSystemCount)
    } else {
        messages
    }
    val protectedHeadTokens = protectedHead.drop(1).sumOf { estimateModelTokens(it.toString()) }
    val historyTokens = compactableMessages.sumOf { estimateModelTokens(it.toString()) }
    val historyChars = compactableMessages.sumOf { it.toString().length }
    val budget = LocalHistoryBudget(
        // Token pressure owns the Work request projection. Character limits remain a separate heap
        // guard and should not accidentally make this route-specific projection more aggressive.
        maxHistoryChars = Int.MAX_VALUE / 4,
        tailChars = WORK_REQUEST_TAIL_CHARS,
        maxSummaryChars = WORK_REQUEST_SUMMARY_CHARS,
        maxToolResultChars = WORK_REQUEST_TOOL_RESULT_CHARS,
        maxHistoryTokens = target,
        tailTokens = minOf(WORK_REQUEST_TAIL_TOKENS, (target * 0.38).toInt().coerceAtLeast(1_024)),
        maxToolResultTokens = WORK_REQUEST_TOOL_RESULT_TOKENS,
    )
    val compacted = compactHistoryWithStaleToolProjection(
        history = compactableMessages,
        compactor = compactor,
        budget = budget,
        extraTokens = beforePressure.toolDefinitionTokens + protectedHeadTokens,
        summaryMode = LocalHistorySummaryMode.WORK,
        currentChars = historyChars,
        currentTokens = historyTokens,
    ) ?: return LocalWorkRequestProjection(
        messages = messages,
        projected = false,
        estimatedTokensBefore = beforePressure.estimatedInputTokens,
        estimatedTokensAfter = beforePressure.estimatedInputTokens,
    )
    val projectedMessages = if (leadingSystemCount > 1) {
        protectedHead + compacted.messages.drop(1)
    } else {
        compacted.messages
    }

    val afterPressure = LocalPromptPressureMeter.measure(
        messages = projectedMessages,
        tools = tools,
        operationalLimitTokens = limit,
    )
    if (afterPressure.estimatedInputTokens >= beforePressure.estimatedInputTokens) {
        return LocalWorkRequestProjection(
            messages = messages,
            projected = false,
            estimatedTokensBefore = beforePressure.estimatedInputTokens,
            estimatedTokensAfter = beforePressure.estimatedInputTokens,
        )
    }
    return LocalWorkRequestProjection(
        messages = projectedMessages,
        projected = true,
        estimatedTokensBefore = beforePressure.estimatedInputTokens,
        estimatedTokensAfter = afterPressure.estimatedInputTokens,
        omittedMessages = compacted.omittedMessages,
    )
}

internal fun workRequestProjectionTargetTokens(operationalLimitTokens: Int): Int {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    return minOf(
        WORK_REQUEST_TARGET_TOKENS,
        maxOf(4_096, (limit * 0.55).toInt()),
    ).coerceAtMost(limit)
}

internal fun workRequestProjectionTriggerTokens(operationalLimitTokens: Int): Int {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    val target = workRequestProjectionTargetTokens(limit)
    return minOf(
        WORK_REQUEST_TRIGGER_TOKENS,
        maxOf(target + 1, (limit * 0.70).toInt()),
    ).coerceAtMost(limit)
}

private const val WORK_REQUEST_TARGET_TOKENS = 64_000
private const val WORK_REQUEST_TRIGGER_TOKENS = 80_000
private const val WORK_REQUEST_TAIL_TOKENS = 24_000
private const val WORK_REQUEST_TAIL_CHARS = 96_000
private const val WORK_REQUEST_SUMMARY_CHARS = 12_000
private const val WORK_REQUEST_TOOL_RESULT_CHARS = 24_000
private const val WORK_REQUEST_TOOL_RESULT_TOKENS = 8_000
