package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure
import com.labteto.dshmobile.local.model.LocalModelPresets
import com.labteto.dshmobile.local.model.LocalModelRuntimeCapabilities

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
    val maxToolResultTokens: Int = DEFAULT_TOOL_RESULT_TOKENS,
    val outputReserveTokens: Int? = null,
    val headroomTokens: Int? = null,
    /** False means the caller owns the semantic-compaction trigger; this budget remains a hard guard. */
    val adaptiveCompactionTrigger: Boolean = true,
)

internal fun localHistoryBudgetFor(
    memoryClassMb: Int,
    pressure: HarnessResourcePressure,
): LocalHistoryBudget = localHistoryBudgetFor(
    memoryClassMb = memoryClassMb,
    pressure = pressure,
    model = null,
)

internal fun localHistoryBudgetFor(
    memoryClassMb: Int,
    pressure: HarnessResourcePressure,
    model: String?,
    baseUrl: String? = null,
    contextWindowTokensOverride: Int? = null,
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

    val routed = routedContextBudget(model, baseUrl, contextWindowTokensOverride)
    return LocalHistoryBudget(
        maxHistoryChars = (base.maxHistoryChars * scale).toInt().coerceAtLeast(180_000),
        tailChars = (base.tailChars * scale).toInt().coerceAtLeast(80_000),
        maxSummaryChars = (base.maxSummaryChars * scale).toInt().coerceAtLeast(8_000),
        maxToolResultChars = (base.maxToolResultChars * scale).toInt().coerceAtLeast(24_000),
        maxHistoryTokens = routed?.messageBudgetTokens,
        tailTokens = routed?.tailTokens,
        maxToolResultTokens = (DEFAULT_TOOL_RESULT_TOKENS * scale).toInt().coerceAtLeast(4_000),
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

private fun routedContextBudget(model: String?, baseUrl: String?, contextWindowTokensOverride: Int? = null): RoutedContextBudget {
    val capabilities = if (model.isNullOrBlank() || baseUrl.isNullOrBlank()) {
        LocalModelRuntimeCapabilities()
    } else {
        LocalModelPresets.runtimeCapabilitiesFor(model, baseUrl)
    }
    val window = capabilities.contextWindowTokens ?: contextWindowTokensOverride?.takeIf { it in 4_096..16_000_000 }
    if (window == null) {
        return RoutedContextBudget(
            messageBudgetTokens = UNKNOWN_ROUTE_OPERATIONAL_INPUT_TOKENS,
            tailTokens = UNKNOWN_ROUTE_TAIL_TOKENS,
            outputReserveTokens = 0,
            headroomTokens = UNKNOWN_ROUTE_HEADROOM_TOKENS,
        )
    }
    val outputReserve = capabilities.defaultMaxOutputTokens
        ?.coerceIn(1, (window / 2).coerceAtLeast(1))
        ?: minOf(DEFAULT_OUTPUT_RESERVE_TOKENS, window / 4)
    val headroom = minOf(DEFAULT_HEADROOM_TOKENS, (window * 0.08).toInt()).coerceAtLeast(1)
    val messageBudget = minOf(
        (window * CONTEXT_TRIGGER_RATIO).toInt(),
        window - outputReserve - headroom,
    ).coerceAtLeast(minOf(UNKNOWN_ROUTE_OPERATIONAL_INPUT_TOKENS, window / 2))
    val tail = ((window - outputReserve) * TAIL_RATIO).toInt().coerceAtLeast(1)
    return RoutedContextBudget(
        messageBudgetTokens = messageBudget,
        tailTokens = tail.coerceAtMost(messageBudget),
        outputReserveTokens = outputReserve,
        headroomTokens = headroom,
    )
}

/**
 * Unknown model capacity must never mean unlimited input. This is an operational exposure ceiling,
 * not a claim about the provider's real context window.
 */
internal fun operationalInputLimitTokens(model: String?, baseUrl: String?, contextWindowTokensOverride: Int? = null): Int =
    routedContextBudget(model, baseUrl, contextWindowTokensOverride).messageBudgetTokens

internal fun documentedContextWindowTokens(model: String?, baseUrl: String?, contextWindowTokensOverride: Int? = null): Int? =
    if (model.isNullOrBlank() || baseUrl.isNullOrBlank()) contextWindowTokensOverride
    else LocalModelPresets.runtimeCapabilitiesFor(model, baseUrl).contextWindowTokens
        ?: contextWindowTokensOverride?.takeIf { it in 4_096..16_000_000 }

private const val UNKNOWN_ROUTE_OPERATIONAL_INPUT_TOKENS = 192_000
private const val UNKNOWN_ROUTE_TAIL_TOKENS = 48_000
private const val UNKNOWN_ROUTE_HEADROOM_TOKENS = 16_000
private const val DEFAULT_OUTPUT_RESERVE_TOKENS = 32_000
private const val DEFAULT_HEADROOM_TOKENS = 65_536
private const val CONTEXT_TRIGGER_RATIO = 0.8
private const val TAIL_RATIO = 0.16
private const val DEFAULT_TOOL_RESULT_TOKENS = 16_000
