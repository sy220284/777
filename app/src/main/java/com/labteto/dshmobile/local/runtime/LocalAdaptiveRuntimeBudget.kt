package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.local.LocalHistoryBudget
import kotlin.math.ceil

/**
 * Treats the configured step value as a baseline instead of a brittle fixed ceiling.
 *
 * Long or audit-style work can borrow turns while the device is healthy. Context pressure reduces
 * expansion because compaction and finishing become more valuable than blindly spending steps.
 */
internal fun adaptiveAgentStepLimit(
    configuredBase: Int,
    task: String,
    contextChars: Int,
    contextBudgetChars: Int,
    pressure: HarnessResourcePressure,
    kind: LocalAgentRunKind,
): Int {
    val base = configuredBase.coerceIn(1, 512)
    if (base == 1) return 1

    val normalized = task.lowercase()
    val complexityHits = ADAPTIVE_COMPLEXITY_CUES.count(normalized::contains).coerceAtMost(6)
    val lengthFactor = (task.length.toDouble() / 1_600.0).coerceIn(0.0, 1.5)
    val contextRatio = if (contextBudgetChars > 0) {
        contextChars.toDouble() / contextBudgetChars.toDouble()
    } else 0.0

    var multiplier = 1.0 +
        (complexityHits * 0.11) +
        (lengthFactor * 0.22) +
        if (kind == LocalAgentRunKind.SUBAGENT || kind == LocalAgentRunKind.AUTOMATION) 0.12 else 0.0

    multiplier *= (1.0 - contextRatio.coerceIn(0.0, 0.9) * 0.12)
    multiplier *= when (pressure) {
        HarnessResourcePressure.LOW -> 1.0
        HarnessResourcePressure.MEDIUM -> 0.9
        HarnessResourcePressure.HIGH -> 0.78
    }

    return ceil(base * multiplier).toInt().coerceIn(base, 512)
}

/**
 * Extends an exhausted soft step budget without resetting the current turn.
 *
 * The configured value remains the baseline. Once the task reaches it, continuation grows from the
 * amount of work already spent, task complexity, context pressure and resource pressure. There is no
 * normal fixed ceiling here: a healthy task keeps receiving budget until the model finishes.
 * Context and resource pressure only reduce the size of each extension; they do not terminate a
 * task that is still making progress. Context overflow is handled by the compaction recovery path.
 */
internal fun nextAdaptiveAgentStepLimit(
    currentLimit: Int,
    configuredBase: Int,
    task: String,
    contextChars: Int,
    contextBudgetChars: Int,
    pressure: HarnessResourcePressure,
    kind: LocalAgentRunKind,
): Int? {
    if (currentLimit <= 0 || currentLimit == Int.MAX_VALUE) return null

    val base = configuredBase.coerceAtLeast(1)
    val normalized = task.lowercase()
    val complexityHits = ADAPTIVE_COMPLEXITY_CUES.count(normalized::contains).coerceAtMost(6)
    val contextRatio = if (contextBudgetChars > 0) {
        contextChars.coerceAtLeast(0).toDouble() / contextBudgetChars.toDouble()
    } else {
        0.0
    }

    val complexityFactor = 1.0 + complexityHits * 0.08 +
        if (kind == LocalAgentRunKind.SUBAGENT || kind == LocalAgentRunKind.AUTOMATION) 0.08 else 0.0
    val contextFactor = when {
        contextRatio >= 1.0 -> 0.55
        contextRatio >= 0.85 -> 0.7
        contextRatio >= 0.65 -> 0.85
        else -> 1.0
    }
    val pressureFactor = when (pressure) {
        HarnessResourcePressure.LOW -> 1.0
        HarnessResourcePressure.MEDIUM -> 0.85
        HarnessResourcePressure.HIGH -> 0.7
    }

    val growth = ceil(
        maxOf(base.toDouble(), currentLimit * 0.5) *
            complexityFactor *
            contextFactor *
            pressureFactor,
    ).toLong().coerceAtLeast(1L)
    val next = currentLimit.toLong() + growth
    return next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * Continuously shrinks the model-visible copy of tool output as history fills up.
 *
 * The complete output remains in LocalToolOutputStore and can be paged with tool_output_read.
 */
internal fun adaptiveToolResultBudget(
    base: LocalHistoryBudget,
    currentHistoryChars: Int,
    currentHistoryTokens: Int,
): LocalHistoryBudget {
    val charRatio = if (base.maxHistoryChars > 0) {
        currentHistoryChars.toDouble() / base.maxHistoryChars.toDouble()
    } else 0.0
    val tokenRatio = base.maxHistoryTokens?.takeIf { it > 0 }?.let {
        currentHistoryTokens.toDouble() / it.toDouble()
    } ?: 0.0
    val load = maxOf(charRatio, tokenRatio).coerceIn(0.0, 1.2)
    val scale = (1.0 - load * 0.65).coerceIn(0.35, 1.0)

    return base.copy(
        maxToolResultChars = (base.maxToolResultChars * scale).toInt()
            .coerceAtLeast(minOf(8_000, base.maxToolResultChars))
            .coerceAtMost(base.maxToolResultChars),
        maxToolResultTokens = (base.maxToolResultTokens * scale).toInt()
            .coerceAtLeast(minOf(2_000, base.maxToolResultTokens))
            .coerceAtMost(base.maxToolResultTokens),
    )
}

private val ADAPTIVE_COMPLEXITY_CUES = listOf(
    "全量", "完整", "审计", "排查", "修复", "测试", "回归", "逐个", "遍历", "并行",
    "audit", "review", "fix", "test", "regression", "scan", "all files",
)
