package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Derives a bounded Work-only request surface from the durable model history.
 *
 * Work is state-driven rather than transcript-driven: complete facts stay durable, the model sees
 * a trusted active-work checkpoint plus a recent causal tail, and old tool payloads leave the hot
 * request early while remaining recoverable through tool_output_read/session_event_search.
 */
internal data class LocalWorkRequestProjection(
    val messages: List<JsonObject>,
    val projected: Boolean,
    val estimatedTokensBefore: Int,
    val estimatedTokensAfter: Int,
    val omittedMessages: Int = 0,
    val preProjectionAssessment: LocalWorkStepContextAssessment? = null,
)

internal fun workSteadyStateHistoryBudget(
    base: LocalHistoryBudget,
    currentHistoryTokens: Int,
    extraTokens: Int,
    state: LocalHarnessState,
): LocalHistoryBudget {
    val profile = state.modelState.modelSelection.activeProfile
    return workSteadyStateHistoryBudget(
        base = base,
        currentHistoryTokens = currentHistoryTokens,
        extraTokens = extraTokens,
        cachePolicy = LocalModelPresets.promptCachePolicyFor(
            model = state.modelState.model,
            baseUrl = state.modelState.baseUrl,
            protocol = profile?.protocol ?: LocalModelPresets.protocolFor(state.modelState.model, state.modelState.baseUrl),
            authKind = profile?.authKind ?: LocalModelAuthKind.API_KEY,
        ),
    )
}

internal fun workSteadyStateHistoryBudget(
    base: LocalHistoryBudget,
    currentHistoryTokens: Int,
    extraTokens: Int = 0,
    cachePolicy: LocalPromptCachePolicy = LocalPromptCachePolicy(),
): LocalHistoryBudget {
    val operationalLimit = base.maxHistoryTokens ?: return base
    val steadyBase = if (cachePolicy.allowAdaptiveEarlyCompaction) {
        base
    } else {
        base.copy(adaptiveCompactionTrigger = false)
    }
    val totalRequestTokens = currentHistoryTokens.toLong() + extraTokens.coerceAtLeast(0).toLong()
    if (totalRequestTokens <= workRequestProjectionTriggerTokens(operationalLimit, cachePolicy).toLong()) {
        return steadyBase
    }
    val target = workRequestProjectionTargetTokens(operationalLimit, cachePolicy)
    val tailTarget = workRequestTailTokens(target, cachePolicy)
    return steadyBase.copy(
        maxHistoryTokens = target,
        tailTokens = minOf(
            steadyBase.tailTokens ?: tailTarget,
            tailTarget,
            (target * 0.38).toInt().coerceAtLeast(1_024),
        ),
        maxSummaryChars = minOf(steadyBase.maxSummaryChars, WORK_REQUEST_SUMMARY_CHARS),
        maxToolResultChars = minOf(steadyBase.maxToolResultChars, WORK_REQUEST_TOOL_RESULT_CHARS),
        maxToolResultTokens = minOf(steadyBase.maxToolResultTokens, WORK_REQUEST_TOOL_RESULT_TOKENS),
    )
}

internal fun projectWorkRequestContext(
    messages: List<JsonObject>,
    tools: JsonArray,
    compactor: LocalHistoryCompactor,
    operationalLimitTokens: Int,
    measuredPressure: LocalPromptPressure? = null,
    previousPressure: LocalPromptPressure? = null,
    structuredWorkState: LocalStructuredWorkState? = null,
    cachePolicy: LocalPromptCachePolicy = LocalPromptCachePolicy(),
    allowSemanticProjection: Boolean = true,
): LocalWorkRequestProjection {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    val beforePressure = measuredPressure ?: LocalPromptPressureMeter.measure(
        messages = messages,
        tools = tools,
        operationalLimitTokens = limit,
    )
    val budget = workRequestBudget(limit, cachePolicy)

    // Large command/file outputs are the fastest-growing part of Work history. No oversized result
    // stays verbatim in the hot request; full payloads remain recoverable from LocalToolOutputStore.
    val staleToolProjection = projectStaleToolResults(
        history = messages,
        budget = budget,
        keepRecentToolResults = 0,
    )
    val toolProjectedMessages = staleToolProjection?.messages ?: messages
    val toolProjectedPressure = if (staleToolProjection != null) {
        LocalPromptPressureMeter.measure(
            messages = toolProjectedMessages,
            tools = tools,
            operationalLimitTokens = limit,
        )
    } else {
        beforePressure
    }

    val baseTrigger = workRequestProjectionTriggerTokens(limit, cachePolicy)
    val assessment = assessWorkStepContext(
        current = toolProjectedPressure,
        previous = previousPressure,
        targetTokens = workRequestProjectionTargetTokens(limit, cachePolicy),
        baseTriggerTokens = baseTrigger,
        growthCurrent = beforePressure,
        allowAdaptiveEarlyCompaction = cachePolicy.allowAdaptiveEarlyCompaction,
    )
    val trigger = assessment.effectiveProjectionTriggerTokens
    if (
        !allowSemanticProjection ||
        (!assessment.recommendsCompaction && toolProjectedPressure.estimatedInputTokens < trigger)
    ) {
        return LocalWorkRequestProjection(
            messages = toolProjectedMessages,
            projected = staleToolProjection != null,
            estimatedTokensBefore = beforePressure.estimatedInputTokens,
            estimatedTokensAfter = toolProjectedPressure.estimatedInputTokens,
            preProjectionAssessment = assessment,
        )
    }

    val leadingSystemCount = toolProjectedMessages.takeWhile { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "system"
    }.size
    val protectedHead = toolProjectedMessages.take(leadingSystemCount)
    val compactableMessages = if (leadingSystemCount > 1) {
        listOf(protectedHead.first()) + toolProjectedMessages.drop(leadingSystemCount)
    } else {
        toolProjectedMessages
    }
    val protectedHeadTokens = protectedHead.drop(1).sumOf { estimateModelTokens(it.toString()) }
    val historyTokens = compactableMessages.sumOf { estimateModelTokens(it.toString()) }
    val historyChars = compactableMessages.sumOf { it.toString().length }

    val compacted = compactHistoryWithStaleToolProjection(
        history = compactableMessages,
        compactor = compactor,
        budget = budget,
        extraTokens = toolProjectedPressure.toolDefinitionTokens + protectedHeadTokens,
        summaryMode = LocalHistorySummaryMode.WORK,
        currentChars = historyChars,
        currentTokens = historyTokens,
        structuredWorkState = structuredWorkState,
    ) ?: return LocalWorkRequestProjection(
        messages = toolProjectedMessages,
        projected = staleToolProjection != null,
        estimatedTokensBefore = beforePressure.estimatedInputTokens,
        estimatedTokensAfter = toolProjectedPressure.estimatedInputTokens,
        preProjectionAssessment = assessment,
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
    if (afterPressure.estimatedInputTokens >= toolProjectedPressure.estimatedInputTokens) {
        return LocalWorkRequestProjection(
            messages = toolProjectedMessages,
            projected = staleToolProjection != null,
            estimatedTokensBefore = beforePressure.estimatedInputTokens,
            estimatedTokensAfter = toolProjectedPressure.estimatedInputTokens,
            preProjectionAssessment = assessment,
        )
    }
    return LocalWorkRequestProjection(
        messages = projectedMessages,
        projected = true,
        estimatedTokensBefore = beforePressure.estimatedInputTokens,
        estimatedTokensAfter = afterPressure.estimatedInputTokens,
        omittedMessages = compacted.omittedMessages,
        preProjectionAssessment = assessment,
    )
}

internal fun workRequestProjectionTargetTokens(
    operationalLimitTokens: Int,
    cachePolicy: LocalPromptCachePolicy = LocalPromptCachePolicy(),
): Int {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    val ratio = cachePolicy.workProjectionTargetRatioPermille
    if (ratio != null) {
        return maxOf(
            WORK_REQUEST_TARGET_TOKENS,
            ((limit.toLong() * ratio.coerceIn(1, 999)) / 1_000L).toInt(),
        ).coerceAtMost(limit)
    }
    return minOf(
        WORK_REQUEST_TARGET_TOKENS,
        maxOf(4_096, (limit * 0.55).toInt()),
    ).coerceAtMost(limit)
}

internal fun workRequestProjectionTriggerTokens(
    operationalLimitTokens: Int,
    cachePolicy: LocalPromptCachePolicy = LocalPromptCachePolicy(),
): Int {
    val limit = operationalLimitTokens.coerceAtLeast(1)
    val target = workRequestProjectionTargetTokens(limit, cachePolicy)
    val ratio = cachePolicy.workProjectionTriggerRatioPermille
    if (ratio != null) {
        return maxOf(
            target + 1,
            WORK_REQUEST_TRIGGER_TOKENS,
            ((limit.toLong() * ratio.coerceIn(1, 1_000)) / 1_000L).toInt(),
        ).coerceAtMost(limit)
    }
    return minOf(
        WORK_REQUEST_TRIGGER_TOKENS,
        maxOf(target + 1, (limit * 0.70).toInt()),
    ).coerceAtMost(limit)
}

private fun workRequestTailTokens(
    targetTokens: Int,
    cachePolicy: LocalPromptCachePolicy,
): Int {
    if (cachePolicy.workProjectionTargetRatioPermille == null) return WORK_REQUEST_TAIL_TOKENS
    return maxOf(
        WORK_REQUEST_TAIL_TOKENS,
        (targetTokens * CACHE_AWARE_TAIL_RATIO).toInt(),
    ).coerceAtMost(CACHE_AWARE_MAX_TAIL_TOKENS)
}

private fun workRequestBudget(
    limit: Int,
    cachePolicy: LocalPromptCachePolicy,
): LocalHistoryBudget {
    val target = workRequestProjectionTargetTokens(limit, cachePolicy)
    val tailTarget = workRequestTailTokens(target, cachePolicy)
    return LocalHistoryBudget(
        maxHistoryChars = Int.MAX_VALUE / 4,
        tailChars = if (cachePolicy.workProjectionTargetRatioPermille == null) {
            WORK_REQUEST_TAIL_CHARS
        } else {
            CACHE_AWARE_TAIL_CHARS
        },
        maxSummaryChars = WORK_REQUEST_SUMMARY_CHARS,
        maxToolResultChars = WORK_REQUEST_TOOL_RESULT_CHARS,
        maxHistoryTokens = target,
        tailTokens = minOf(
            tailTarget,
            (target * 0.38).toInt().coerceAtLeast(1_024),
        ),
        maxToolResultTokens = WORK_REQUEST_TOOL_RESULT_TOKENS,
        adaptiveCompactionTrigger = cachePolicy.allowAdaptiveEarlyCompaction,
    )
}

private const val WORK_REQUEST_TARGET_TOKENS = 28_000
private const val WORK_REQUEST_TRIGGER_TOKENS = 36_000
private const val WORK_REQUEST_TAIL_TOKENS = 10_000
private const val WORK_REQUEST_TAIL_CHARS = 40_000
private const val WORK_REQUEST_SUMMARY_CHARS = 8_000
private const val WORK_REQUEST_TOOL_RESULT_CHARS = 4_096
private const val WORK_REQUEST_TOOL_RESULT_TOKENS = 1_600
private const val CACHE_AWARE_TAIL_RATIO = 0.16
private const val CACHE_AWARE_MAX_TAIL_TOKENS = 64_000
private const val CACHE_AWARE_TAIL_CHARS = 160_000
