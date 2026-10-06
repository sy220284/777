package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.agent.LocalSubagentCompactionPolicy
import com.labteto.dshmobile.local.agent.LocalSubagentHistoryPolicy
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalPromptCachePolicy

/** Work-owned history budget policy supplied to the shared Agent subagent runner. */
internal object LocalWorkSubagentCompactionPolicy : LocalSubagentCompactionPolicy {
    override fun beforeModelStep(
        historyPolicy: LocalSubagentHistoryPolicy,
        history: LocalModelHistoryBuffer,
        subagentId: String,
        completedModelSteps: Int,
        baseBudget: LocalHistoryBudget?,
        cachePolicy: LocalPromptCachePolicy,
    ) {
        if (!shouldProactivelyCompactBeforeModelStep(LocalUsageMode.WORK, completedModelSteps)) return
        atTurnBoundary(historyPolicy, history, subagentId, baseBudget, cachePolicy)
    }

    override fun atTurnBoundary(
        historyPolicy: LocalSubagentHistoryPolicy,
        history: LocalModelHistoryBuffer,
        subagentId: String,
        baseBudget: LocalHistoryBudget?,
        cachePolicy: LocalPromptCachePolicy,
    ) {
        historyPolicy.compactHistory(
            history,
            subagentId,
            baseBudget?.let {
                workSteadyStateHistoryBudget(it, history.estimatedTokens, cachePolicy = cachePolicy)
            },
        )
    }
}
