package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryPolicy
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy

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
