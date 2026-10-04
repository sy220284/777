package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.agent.LocalSubagentHistoryPolicy
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer

internal fun LocalSubagentHistoryPolicy.compactBeforeModelStep(
    history: LocalModelHistoryBuffer,
    subagentId: String,
    completedModelSteps: Int,
    baseBudget: LocalHistoryBudget?,
    cachePolicy: LocalPromptCachePolicy,
) {
    if (!shouldProactivelyCompactBeforeModelStep(LocalUsageMode.WORK, completedModelSteps)) return
    compactAtTurnBoundary(history, subagentId, baseBudget, cachePolicy)
}

internal fun LocalSubagentHistoryPolicy.compactAtTurnBoundary(
    history: LocalModelHistoryBuffer,
    subagentId: String,
    baseBudget: LocalHistoryBudget?,
    cachePolicy: LocalPromptCachePolicy,
) = compactHistory(
    history,
    subagentId,
    baseBudget?.let {
        workSteadyStateHistoryBudget(it, history.estimatedTokens, cachePolicy = cachePolicy)
    },
)
