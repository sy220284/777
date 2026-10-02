package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalHistoryCompaction
import com.labteto.dshmobile.local.LocalHistoryCompactor
import com.labteto.dshmobile.local.LocalHistorySummaryMode
import com.labteto.dshmobile.local.LocalStructuredWorkState
import com.labteto.dshmobile.local.compactHistoryWithStaleToolProjection

/** Model-history compaction policy stays separate from the mutable history container. */
internal fun LocalModelHistoryBuffer.compactOverflow(
    compactor: LocalHistoryCompactor,
    summaryMode: LocalHistorySummaryMode,
): LocalHistoryCompaction? {
    val compacted = compactor.compactForOverflow(snapshot(), summaryMode) ?: return null
    reset(compacted.messages)
    return compacted
}

internal fun LocalModelHistoryBuffer.compact(
    compactor: LocalHistoryCompactor,
    budget: LocalHistoryBudget,
    extraTokens: Int = 0,
    summaryMode: LocalHistorySummaryMode,
    structuredWorkState: LocalStructuredWorkState? = null,
): LocalHistoryCompaction? {
    val compacted = compactHistoryWithStaleToolProjection(
        history = snapshot(),
        compactor = compactor,
        budget = budget,
        extraTokens = extraTokens,
        summaryMode = summaryMode,
        currentChars = encodedChars,
        currentTokens = estimatedTokens,
        structuredWorkState = structuredWorkState,
    ) ?: return null
    reset(compacted.messages)
    return compacted
}
