package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import com.labteto.dshmobile.local.LocalToolOutputStore
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.adaptiveToolResultBudget
import com.labteto.dshmobile.local.model.projectRecoverableToolResult
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore

/** Work-owned projection/spill policy for model-visible tool results. */
internal class LocalWorkToolResultRuntime(
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val toolOutputStore: LocalToolOutputStore,
) {
    internal fun retain(
        binding: LocalWorkRunBinding,
        callId: String?,
        result: String,
        retention: ToolResultRetention = ToolResultRetention.DURABLE,
    ): String {
        val history = binding.runHandle.modelHistory
        val budget = adaptiveToolResultBudget(
            base = runtimeStateStore.historyBudgetFor(
                binding.aggregateSnapshot(),
                runtimeStateStore.resourceSnapshot(),
            ),
            currentHistoryChars = history.encodedChars,
            currentHistoryTokens = history.estimatedTokens,
        )
        return projectRecoverableToolResult(
            value = result,
            retention = retention,
            usageMode = LocalUsageMode.WORK,
            budget = budget,
            callId = callId,
            spill = { id, value ->
                toolOutputStore.store(binding.sessionId, id, value) != null
            },
        ).text
    }
}
