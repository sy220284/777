package com.labteto.dshmobile.local.agent
import com.labteto.dshmobile.local.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        history: List<JsonObject>,
    ): String {
        budget ?: return output
        val adaptiveBudget = adaptiveToolResultBudget(
            base = budget,
            currentHistoryChars = history.sumOf { it.toString().length },
            currentHistoryTokens = history.sumOf { estimateModelTokens(it.toString()) },
        )
        val stored = spillToolOutput(callId, output)
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
        history: MutableList<JsonObject>,
        subagentId: String,
        budget: LocalHistoryBudget?,
    ) {
        projectStaleSubagentToolResults(history, budget, subagentId, eventLog())
        val compaction = historyCompactor.compact(history, budget) ?: return
        history.clear()
        history += compaction.messages
        eventLog().append("subagent/compaction", buildJsonObject {
            put("agent_id", subagentId)
            put("omitted_messages", compaction.omittedMessages)
            put("summary", compaction.summary)
            put("estimated_tokens_before", compaction.estimatedTokensBefore)
            put("estimated_tokens_after", compaction.estimatedTokensAfter)
        })
    }

    fun rememberProgress(progress: ArrayDeque<String>, item: String) =
        progress.addLast(item).also { while (progress.size > MAX_PROGRESS_ITEMS) progress.removeFirst() }

    private companion object {
        const val MAX_PROGRESS_ITEMS = 6
    }
}
