package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalHistoryBudget
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Keeps full tool payloads in LocalToolOutputStore while shrinking stale model-visible copies.
 * Recent tool results stay verbatim; older large results become a bounded preview plus call reference.
 */
internal fun projectStaleToolResults(
    history: List<JsonObject>,
    budget: LocalHistoryBudget,
    keepRecentToolResults: Int = 4,
    protectedRecentMaxBytes: Int? = null,
): LocalHistoryCompaction? {
    val toolIndexes = history.indices.filter { index ->
        history[index]["role"]?.jsonPrimitive?.contentOrNull == "tool"
    }

    val protected = toolIndexes.takeLast(keepRecentToolResults).toSet()
    val maxChars = minOf(4_096, (budget.maxToolResultChars / 4).coerceAtLeast(1_024))
    val maxTokens = minOf(1_200, (budget.maxToolResultTokens / 4).coerceAtLeast(256))
    val projected = history.toMutableList()
    var changed = false

    toolIndexes.forEach { index ->
        val message = history[index]
        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        if (
            index in protected &&
            (
                protectedRecentMaxBytes == null ||
                    content.toByteArray(Charsets.UTF_8).size <= protectedRecentMaxBytes
            )
        ) {
            return@forEach
        }
        if (content.length <= maxChars) return@forEach
        val retained = retainTextForModel(content, maxTokens = maxTokens, maxChars = maxChars)
        if (!retained.truncated) return@forEach
        val callId = message["tool_call_id"]?.jsonPrimitive?.contentOrNull
        projected[index] = buildJsonObject {
            message.forEach { (key, value) ->
                if (key != "content") put(key, value)
            }
            put(
                "content",
                buildString {
                    append(retained.text)
                    append("\n[旧工具结果已从实时模型上下文衰减")
                    if (!callId.isNullOrBlank()) {
                        append("；完整原文可用 tool_output_read(call_id=")
                        append(callId)
                        append(") 分段读取")
                    }
                    append("]")
                },
            )
        }
        changed = true
    }

    if (!changed) return null
    val before = history.sumOf { estimateModelTokens(it.toString()) }
    val after = projected.sumOf { estimateModelTokens(it.toString()) }
    if (after >= before) return null
    return LocalHistoryCompaction(
        messages = projected,
        omittedMessages = 0,
        summary = "旧工具结果已降级为有界模型投影，完整事实仍保留在工具结果存储。",
        estimatedTokensBefore = before,
        estimatedTokensAfter = after,
    )
}

internal fun compactHistoryWithStaleToolProjection(
    history: List<JsonObject>,
    compactor: LocalHistoryCompactor,
    budget: LocalHistoryBudget,
    extraTokens: Int,
    summaryMode: LocalHistorySummaryMode,
    currentChars: Int,
    currentTokens: Int,
    structuredWorkState: LocalHistorySummaryInput? = null,
): LocalHistoryCompaction? {
    val projection = projectStaleToolResults(history, budget)
    val source = projection?.messages ?: history
    val compaction = compactor.compact(
        history = source,
        budget = budget,
        currentChars = if (projection == null) currentChars else source.sumOf { it.toString().length },
        currentTokens = projection?.estimatedTokensAfter ?: currentTokens,
        extraTokens = extraTokens,
        summaryMode = summaryMode,
        structuredWorkState = structuredWorkState,
    ) ?: return projection
    return compaction.copy(
        estimatedTokensBefore = projection?.estimatedTokensBefore ?: compaction.estimatedTokensBefore,
    )
}

