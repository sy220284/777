package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy

/**
 * Provider-owned policy for subagent history compaction.
 *
 * The Agent capability owns the execution lifecycle but does not interpret Work-specific history
 * budgets. Feature composition supplies the policy explicitly.
 */
internal interface LocalSubagentCompactionPolicy {
    fun beforeModelStep(
        historyPolicy: LocalSubagentHistoryPolicy,
        history: LocalModelHistoryBuffer,
        subagentId: String,
        completedModelSteps: Int,
        baseBudget: LocalHistoryBudget?,
        cachePolicy: LocalPromptCachePolicy,
    )

    fun atTurnBoundary(
        historyPolicy: LocalSubagentHistoryPolicy,
        history: LocalModelHistoryBuffer,
        subagentId: String,
        baseBudget: LocalHistoryBudget?,
        cachePolicy: LocalPromptCachePolicy,
    )
}
