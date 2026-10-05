package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.LocalRequestContextAssessment
import com.labteto.dshmobile.local.model.LocalPromptPressure

/**
 * Evaluates whether one Work request carries a reasonable amount of model-visible context.
 *
 * This is deliberately based on measurable prompt composition rather than agent step count or a
 * semantic guess about whether the previous step "made progress". It may recommend earlier context
 * compaction, but it never stops the task or removes capabilities.
 */
internal enum class LocalWorkStepContextStatus {
    HEALTHY,
    WATCH,
    COMPACT,
}

internal data class LocalWorkStepContextAssessment(
    val status: LocalWorkStepContextStatus,
    val estimatedInputTokens: Int,
    val historyTokens: Int,
    val toolDefinitionTokens: Int,
    val historyRatioPermille: Int,
    val toolRatioPermille: Int,
    val inputGrowthTokens: Int,
    val historyGrowthTokens: Int,
    val effectiveProjectionTriggerTokens: Int,
    val reasons: List<String>,
) {
    val recommendsCompaction: Boolean
        get() = status == LocalWorkStepContextStatus.COMPACT
}

internal fun LocalWorkStepContextAssessment.toRequestContextAssessment() =
    LocalRequestContextAssessment(
        status = status.name.lowercase(),
        estimatedInputTokens = estimatedInputTokens,
        historyTokens = historyTokens,
        toolDefinitionTokens = toolDefinitionTokens,
        historyRatioPermille = historyRatioPermille,
        toolRatioPermille = toolRatioPermille,
        inputGrowthTokens = inputGrowthTokens,
        historyGrowthTokens = historyGrowthTokens,
        effectiveProjectionTriggerTokens = effectiveProjectionTriggerTokens,
        reasons = reasons,
    )

internal fun assessWorkStepContext(
    current: LocalPromptPressure,
    previous: LocalPromptPressure?,
    targetTokens: Int,
    baseTriggerTokens: Int,
    growthCurrent: LocalPromptPressure = current,
    allowAdaptiveEarlyCompaction: Boolean = true,
): LocalWorkStepContextAssessment {
    val total = current.estimatedInputTokens.coerceAtLeast(0)
    val target = targetTokens.coerceAtLeast(1)
    val baseTrigger = baseTriggerTokens.coerceAtLeast(target + 1)
    val historyRatio = ratioPermille(current.historyTokens, total)
    val toolRatio = ratioPermille(current.toolDefinitionTokens, total)
    // Growth must compare the same source-history coordinate system on both sides. Request-only
    // projection can be much smaller than durable history; comparing a projected previous request
    // with an unprojected current source creates false growth and causes repeated cache-breaking
    // compaction.
    val inputGrowth = previous?.let {
        growthCurrent.estimatedInputTokens - it.estimatedInputTokens
    } ?: 0
    val historyGrowth = previous?.let {
        growthCurrent.historyTokens - it.historyTokens
    } ?: 0

    val historyDominant = historyRatio >= HISTORY_DOMINANT_PERMILLE
    val toolHeavy =
        current.toolDefinitionTokens >= TOOL_DEFINITION_WATCH_TOKENS ||
            toolRatio >= TOOL_DEFINITION_WATCH_PERMILLE
    val rapidHistoryGrowth = previous != null && historyGrowth >= RAPID_HISTORY_GROWTH_TOKENS

    // Keep stable large prefixes cache-friendly. Early compaction is reserved for requests whose
    // history is both dominant and still growing quickly; otherwise the normal absolute trigger
    // remains authoritative.
    val earlyTrigger = minOf(
        baseTrigger,
        maxOf(
            target + MIN_TARGET_HEADROOM_TOKENS,
            (baseTrigger * EARLY_TRIGGER_PERMILLE) / 1_000,
        ),
    )
    val earlyCompaction =
        allowAdaptiveEarlyCompaction &&
            total >= earlyTrigger &&
            historyDominant &&
            rapidHistoryGrowth
    val absoluteCompaction = total >= baseTrigger

    val watchFloor = minOf(
        baseTrigger,
        maxOf(MIN_WATCH_TOKENS, target - WATCH_BELOW_TARGET_TOKENS),
    )
    val reasons = buildList {
        if (historyDominant) add("history_dominant")
        if (rapidHistoryGrowth) add("history_growing_fast")
        if (toolHeavy) add("tool_schema_heavy")
        if (absoluteCompaction) add("absolute_pressure")
        else if (earlyCompaction) add("adaptive_history_pressure")
    }

    val status = when {
        absoluteCompaction || earlyCompaction -> LocalWorkStepContextStatus.COMPACT
        total >= watchFloor && (historyDominant || rapidHistoryGrowth || toolHeavy) ->
            LocalWorkStepContextStatus.WATCH
        else -> LocalWorkStepContextStatus.HEALTHY
    }
    return LocalWorkStepContextAssessment(
        status = status,
        estimatedInputTokens = total,
        historyTokens = current.historyTokens,
        toolDefinitionTokens = current.toolDefinitionTokens,
        historyRatioPermille = historyRatio,
        toolRatioPermille = toolRatio,
        inputGrowthTokens = inputGrowth,
        historyGrowthTokens = historyGrowth,
        effectiveProjectionTriggerTokens = if (earlyCompaction) earlyTrigger else baseTrigger,
        reasons = reasons,
    )
}

private fun ratioPermille(value: Int, total: Int): Int =
    if (total <= 0 || value <= 0) 0
    else ((value.toLong() * 1_000L) / total.toLong()).toInt().coerceIn(0, 1_000)

private const val HISTORY_DOMINANT_PERMILLE = 680
private const val TOOL_DEFINITION_WATCH_PERMILLE = 250
private const val TOOL_DEFINITION_WATCH_TOKENS = 4_500
private const val RAPID_HISTORY_GROWTH_TOKENS = 1_500
private const val EARLY_TRIGGER_PERMILLE = 880
private const val MIN_TARGET_HEADROOM_TOKENS = 2_000
private const val MIN_WATCH_TOKENS = 20_000
private const val WATCH_BELOW_TARGET_TOKENS = 4_000
