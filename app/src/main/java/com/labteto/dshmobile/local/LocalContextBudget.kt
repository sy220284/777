package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourcePressure

internal data class LocalHistoryBudget(
    val maxHistoryChars: Int,
    val tailChars: Int,
    val maxSummaryChars: Int,
    val maxToolResultChars: Int,
)

internal fun localHistoryBudgetFor(
    memoryClassMb: Int,
    pressure: HarnessResourcePressure,
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
    return LocalHistoryBudget(
        maxHistoryChars = (base.maxHistoryChars * scale).toInt().coerceAtLeast(180_000),
        tailChars = (base.tailChars * scale).toInt().coerceAtLeast(80_000),
        maxSummaryChars = (base.maxSummaryChars * scale).toInt().coerceAtLeast(8_000),
        maxToolResultChars = (base.maxToolResultChars * scale).toInt().coerceAtLeast(24_000),
    )
}
