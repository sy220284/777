package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.compact
import com.labteto.dshmobile.local.model.projectRecoverableToolResult
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject

/** Owns subagent history retention, compaction and bounded progress memory. */
internal class LocalSubagentHistoryPolicy(
    private val spillToolOutput: (String, String) -> Boolean,
    private val historyCompactor: LocalHistoryCompactor,
    private val eventLog: () -> LocalSessionEventLog,
) {
    fun retainToolResult(
        callId: String,
        output: String,
        retention: com.labteto.dshmobile.harness.tools.ToolResultRetention,
    ): String = projectRecoverableToolResult(
        value = output,
        retention = retention,
        usageMode = LocalUsageMode.WORK,
        callId = callId,
        spill = spillToolOutput,
    ).text

    fun compactHistory(
        history: LocalModelHistoryBuffer,
        subagentId: String,
        budget: LocalHistoryBudget?,
    ) {
        val compaction = if (budget != null) {
            history.compact(
                compactor = historyCompactor,
                budget = budget,
                summaryMode = LocalHistorySummaryMode.WORK,
            )
        } else {
            historyCompactor.compact(
                history = history.snapshot(),
                summaryMode = LocalHistorySummaryMode.WORK,
            )?.also { history.reset(it.messages) }
        } ?: return
        recordSubagentCompaction(eventLog(), subagentId, compaction)
    }

    fun rememberProgress(progress: ArrayDeque<String>, item: String) =
        progress.addLast(item).also { while (progress.size > MAX_PROGRESS_ITEMS) progress.removeFirst() }

    private companion object {
        const val MAX_PROGRESS_ITEMS = 6
    }
}
