package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure

/**
 * Two independent guards share one policy object:
 * - character limits protect Android heap / serialization work;
 * - token limits protect the routed model context.
 *
 * Unknown third-party models keep the character guard and omit model-window limits rather than
 * guessing a capacity they may not have.
 */
internal data class LocalHistoryBudget(
    val maxHistoryChars: Int,
    val tailChars: Int,
    val maxSummaryChars: Int,
    val maxToolResultChars: Int,
    val maxHistoryTokens: Int? = null,
    val tailTokens: Int? = null,
    val maxToolResultTokens: Int = DEFAULT_WORK_TOOL_RESULT_TOKENS,
    val outputReserveTokens: Int? = null,
    val headroomTokens: Int? = null,
)

internal fun localHistoryBudgetFor(
    memoryClassMb: Int,
    pressure: HarnessResourcePressure,
): LocalHistoryBudget = localHistoryBudgetFor(
    memoryClassMb = memoryClassMb,
    pressure = pressure,
    usageMode = LocalUsageMode.WORK,
    model = null,
)

internal fun localHistoryBudgetFor(
    memoryClassMb: Int,
    pressure: HarnessResourcePressure,
    usageMode: LocalUsageMode,
    model: String?,
): LocalHistoryBudget {
    val base = when {
        memoryClassMb >= 512 -> LocalHistoryBudget(
            maxHistoryChars = 700_000,
            tailChars = 320_000,
            maxSummaryChars = 20_000,
            maxToolResultChars = 60_000,
        )
        memoryClassMb >= 256 -> LocalHistoryBudget(
            maxHistoryChars = 500_000,
            tailChars = 240_000,
            maxSummaryChars = 16_000,
            maxToolResultChars = 50_000,
        )
        else -> LocalHistoryBudget(
            maxHistoryChars = 320_000,
            tailChars = 140_000,
            maxSummaryChars = 12_000,
            maxToolResultChars = 36_000,
        )
    }
    val scale = when (pressure) {
        HarnessResourcePressure.LOW -> 1.0
        HarnessResourcePressure.MEDIUM -> 0.82
        HarnessResourcePressure.HIGH -> 0.65
    }

    val routed = officialDeepSeekContext(model)
    val toolTokensBase = if (usageMode == LocalUsageMode.CHAT) {
        DEFAULT_CHAT_TOOL_RESULT_TOKENS
    } else {
        DEFAULT_WORK_TOOL_RESULT_TOKENS
    }
    return LocalHistoryBudget(
        maxHistoryChars = (base.maxHistoryChars * scale).toInt().coerceAtLeast(180_000),
        tailChars = (base.tailChars * scale).toInt().coerceAtLeast(80_000),
        maxSummaryChars = (base.maxSummaryChars * scale).toInt().coerceAtLeast(8_000),
        maxToolResultChars = (base.maxToolResultChars * scale).toInt().coerceAtLeast(24_000),
        maxHistoryTokens = routed?.messageBudgetTokens,
        tailTokens = routed?.tailTokens,
        maxToolResultTokens = (toolTokensBase * scale).toInt().coerceAtLeast(4_000),
        outputReserveTokens = routed?.outputReserveTokens,
        headroomTokens = routed?.headroomTokens,
    )
}

private data class RoutedContextBudget(
    val messageBudgetTokens: Int,
    val tailTokens: Int,
    val outputReserveTokens: Int,
    val headroomTokens: Int,
)

private fun officialDeepSeekContext(model: String?): RoutedContextBudget? {
    val normalized = model?.trim()?.lowercase() ?: return null
    if (normalized !in OFFICIAL_DEEPSEEK_MODELS) return null

    val messageBudget = minOf(
        (OFFICIAL_CONTEXT_WINDOW_TOKENS * CONTEXT_TRIGGER_RATIO).toInt(),
        OFFICIAL_CONTEXT_WINDOW_TOKENS - OFFICIAL_OUTPUT_RESERVE_TOKENS - OFFICIAL_HEADROOM_TOKENS,
    )
    val tail = ((OFFICIAL_CONTEXT_WINDOW_TOKENS - OFFICIAL_OUTPUT_RESERVE_TOKENS) * TAIL_RATIO)
        .toInt()
        .coerceAtLeast(1)
    return RoutedContextBudget(
        messageBudgetTokens = messageBudget,
        tailTokens = tail,
        outputReserveTokens = OFFICIAL_OUTPUT_RESERVE_TOKENS,
        headroomTokens = OFFICIAL_HEADROOM_TOKENS,
    )
}

private val OFFICIAL_DEEPSEEK_MODELS = setOf("deepseek-flash", "deepseek-v4-pro")
private const val OFFICIAL_CONTEXT_WINDOW_TOKENS = 1_000_000
private const val OFFICIAL_OUTPUT_RESERVE_TOKENS = 256_000
private const val OFFICIAL_HEADROOM_TOKENS = 65_536
private const val CONTEXT_TRIGGER_RATIO = 0.8
private const val TAIL_RATIO = 0.16
private const val DEFAULT_WORK_TOOL_RESULT_TOKENS = 16_000
private const val DEFAULT_CHAT_TOOL_RESULT_TOKENS = 8_000
