package com.labteto.dshmobile.local.agent
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.model.*
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
        budget: LocalHistoryBudget?,
        history: LocalModelHistoryBuffer, retention: com.labteto.dshmobile.harness.tools.ToolResultRetention,
    ): String {
        budget ?: return output
        val adaptiveBudget = adaptiveToolResultBudget(
            base = budget,
            currentHistoryChars = history.encodedChars,
            currentHistoryTokens = history.estimatedTokens,
        )
        val stored = retention == com.labteto.dshmobile.harness.tools.ToolResultRetention.DURABLE && spillToolOutput(callId, output)
        val retained = retainTextForModel(
            value = output,
            maxTokens = adaptiveBudget.maxToolResultTokens,
            maxChars = adaptiveBudget.maxToolResultChars,
        )
        if (!retained.truncated) return retained.text
        val recovery = if (stored) {
            "可调用 tool_output_read，并传入 call_id=$callId 分段读取完整结果。"
        } else {
            "完整结果超过本机私有保留上限；请缩小原查询后重试。"
        }
        return retained.text +
            "\n[已从模型上下文省略 ${retained.omittedBytes} 个 UTF-8 字节；$recovery]"
    }

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
