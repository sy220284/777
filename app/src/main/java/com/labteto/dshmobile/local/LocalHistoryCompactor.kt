package com.labteto.dshmobile.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal data class LocalHistoryCompaction(
    val messages: List<JsonObject>,
    val omittedMessages: Int,
    val summary: String,
    val estimatedTokensBefore: Int = 0,
    val estimatedTokensAfter: Int = 0,
)

internal enum class LocalHistorySummaryMode {
    WORK,
    CHAT,
}

internal fun applyOverflowCompaction(
    history: MutableList<JsonObject>,
    compactor: LocalHistoryCompactor,
    summaryMode: LocalHistorySummaryMode,
): LocalHistoryCompaction? {
    val compacted = compactor.compactForOverflow(history.toList(), summaryMode) ?: return null
    history.clear()
    history += compacted.messages
    return compacted
}

/**
 * Keeps a recent verbatim tail while turning the older part of model history into a bounded,
 * extractive summary. The summary only quotes facts already present in the model-visible history;
 * it never asks another model to invent a recap.
 */
internal class LocalHistoryCompactor(
    private val maxHistoryChars: Int = DEFAULT_MAX_HISTORY_CHARS,
    private val tailChars: Int = DEFAULT_TAIL_CHARS,
    private val maxSummaryChars: Int = DEFAULT_MAX_SUMMARY_CHARS,
) {
    fun compact(
        history: List<JsonObject>,
        budget: LocalHistoryBudget? = null,
        currentChars: Int? = null,
        currentTokens: Int? = null,
        extraTokens: Int = 0,
        summaryMode: LocalHistorySummaryMode = LocalHistorySummaryMode.WORK,
    ): LocalHistoryCompaction? {
        val effectiveMaxHistoryChars = budget?.maxHistoryChars ?: maxHistoryChars
        val effectiveTailChars = budget?.tailChars ?: tailChars
        val effectiveSummaryChars = budget?.maxSummaryChars ?: maxSummaryChars
        val effectiveMaxHistoryTokens = budget?.maxHistoryTokens
        val effectiveTailTokens = budget?.tailTokens
        val encodedChars = currentChars ?: history.sumOf { it.toString().length }
        val encodedTokens = currentTokens ?: history.sumOf { estimateModelTokens(it.toString()) }
        val charPressure = encodedChars > effectiveMaxHistoryChars
        val tokenPressure = effectiveMaxHistoryTokens?.let { encodedTokens + extraTokens > it } == true
        if (history.size < 3 || (!charPressure && !tokenPressure)) return null

        var start = 1
        var keptChars = 0
        var keptTokens = 0
        for (index in history.lastIndex downTo 1) {
            val encoded = history[index].toString()
            keptChars += encoded.length
            keptTokens += estimateModelTokens(encoded)
            if (
                keptChars > effectiveTailChars ||
                (effectiveTailTokens != null && keptTokens > effectiveTailTokens)
            ) {
                start = (index until history.size).firstOrNull { candidate ->
                    history[candidate]["role"].asText() == "user"
                } ?: index
                break
            }
        }
        if (start <= 1 || start >= history.size) return null

        val omitted = history.subList(1, start)
        val summary = buildSummary(omitted, effectiveSummaryChars, summaryMode)
        val compacted = buildList {
            add(history.first())
            add(buildJsonObject {
                put("role", "user")
                put("content", "<compacted-summary>\n$summary\n</compacted-summary>")
            })
            addAll(history.drop(start))
        }
        val estimatedTokensBefore = encodedTokens + extraTokens
        val estimatedTokensAfter = compacted.sumOf { estimateModelTokens(it.toString()) } + extraTokens
        // Compaction is allowed to change durable history only when it actually releases context.
        // A short older span can be smaller than the extractive summary header itself.
        if (estimatedTokensAfter >= estimatedTokensBefore) return null
        return LocalHistoryCompaction(
            messages = compacted,
            omittedMessages = omitted.size,
            summary = summary,
            estimatedTokensBefore = estimatedTokensBefore,
            estimatedTokensAfter = estimatedTokensAfter,
        )
    }

    fun compactForOverflow(
        history: List<JsonObject>,
        summaryMode: LocalHistorySummaryMode = LocalHistorySummaryMode.WORK,
    ): LocalHistoryCompaction? {
        if (history.size < 3) return null

        // Preserve the leading system prefix exactly. Request-only context such as persona/profile
        // facts is injected immediately after the base system prompt and must survive emergency
        // recovery. A later system message (for example the current chat turn's dynamic context)
        // stays at its original tail boundary instead of being hoisted ahead of retained history.
        val leadingSystemCount = history.takeWhile { it["role"].asText() == "system" }.size
        val protectedHead = history.take(leadingSystemCount)
        val firstBodyIndex = leadingSystemCount
        if (firstBodyIndex >= history.lastIndex) return null

        val latestTailSystemIndex = (history.lastIndex downTo firstBodyIndex)
            .firstOrNull { history[it]["role"].asText() == "system" }
        val compactableEndExclusive = latestTailSystemIndex ?: history.size
        val compactableBody = history.subList(firstBodyIndex, compactableEndExclusive)
        if (compactableBody.size < 2) return null

        val leadingSystem = protectedHead.firstOrNull() ?: buildJsonObject {
            put("role", "system")
            put("content", "")
        }
        val protectedSuffix = latestTailSystemIndex?.let { history.subList(it, history.size) }.orEmpty()
        val protectedTokens =
            protectedHead.drop(1).sumOf { estimateModelTokens(it.toString()) } +
                protectedSuffix.sumOf { estimateModelTokens(it.toString()) }
        val working = listOf(leadingSystem) + compactableBody
        val encodedChars = working.sumOf { it.toString().length }
        val encodedTokens = working.sumOf { estimateModelTokens(it.toString()) }
        val aggressiveTailChars = minOf(
            tailChars,
            maxOf(1_000, encodedChars / 3),
        )
        val aggressiveTailTokens = maxOf(512, encodedTokens / 3)
        val aggressiveSummaryChars = minOf(
            maxSummaryChars,
            maxOf(1_000, encodedChars / 8),
        )
        val overflowBudget = LocalHistoryBudget(
            maxHistoryChars = 0,
            tailChars = aggressiveTailChars,
            maxSummaryChars = aggressiveSummaryChars,
            maxToolResultChars = 1,
            maxHistoryTokens = null,
            tailTokens = aggressiveTailTokens,
            maxToolResultTokens = 1,
        )
        val compacted = compact(
            history = working,
            budget = overflowBudget,
            currentChars = encodedChars,
            currentTokens = encodedTokens,
            extraTokens = protectedTokens,
            summaryMode = summaryMode,
        ) ?: return null

        val rebuilt = buildList {
            addAll(protectedHead)
            addAll(compacted.messages.drop(1))
            addAll(protectedSuffix)
        }
        val before = history.sumOf { estimateModelTokens(it.toString()) }
        val after = rebuilt.sumOf { estimateModelTokens(it.toString()) }
        if (after >= before) return null
        return compacted.copy(
            messages = rebuilt,
            estimatedTokensBefore = before,
            estimatedTokensAfter = after,
        )
    }

    private fun buildSummary(
        messages: List<JsonObject>,
        summaryLimit: Int,
        summaryMode: LocalHistorySummaryMode,
    ): String {
        val user = recentText(messages, "user", maxItems = 8, maxPerItem = 1_200)
        val assistant = recentText(messages, "assistant", maxItems = 6, maxPerItem = 1_200)
        val tools = recentTools(messages, maxItems = 12)

        val text = buildString {
            when (summaryMode) {
                LocalHistorySummaryMode.WORK -> {
                    val sections = structuredWorkSummary(messages, summaryLimit)
                    append("以下是较早会话的结构化提取式检查点，共折叠 ")
                    append(messages.size)
                    append(" 条模型消息。所有条目只摘自原会话；当前目标、计划、任务清单与工作区文件仍以实时状态为准。")
                    appendSummarySection("目标与需求", sections.goals)
                    appendSummarySection("约束与边界", sections.constraints)
                    appendSummarySection("关键决定与阶段结论", sections.decisions)
                    appendSummarySection("失败尝试与风险", sections.failures)
                    appendSummarySection("未完成事项", sections.unfinished)
                    appendSummarySection("其他阶段进展", sections.progress)
                    if (tools.isNotEmpty()) {
                        append("\n\n已涉及工具：")
                        append(tools.joinToString("、"))
                    }
                }
                LocalHistorySummaryMode.CHAT -> {
                    append("以下是较早聊天的提取式连续性摘要，共折叠 ")
                    append(messages.size)
                    append(" 条模型消息。内容只摘自原聊天；当前人物设定、关系状态、长期记忆与群聊成员状态仍以实时状态为准。")
                    if (user.isNotEmpty()) {
                        append("\n\n较早用户表达与事件：")
                        user.forEach { append("\n- ").append(it) }
                    }
                    if (assistant.isNotEmpty()) {
                        append("\n\n较早角色回应与互动：")
                        assistant.forEach { append("\n- ").append(it) }
                    }
                }
            }
        }
        return truncateWithoutSplittingSurrogatePair(text, summaryLimit)
    }

    private data class WorkSummarySections(
        val goals: List<String>,
        val constraints: List<String>,
        val decisions: List<String>,
        val failures: List<String>,
        val unfinished: List<String>,
        val progress: List<String>,
    )

    private fun StringBuilder.appendSummarySection(title: String, values: List<String>) {
        if (values.isEmpty()) return
        append("\n\n").append(title).append("：")
        values.forEach { append("\n- ").append(it) }
    }

    private fun structuredWorkSummary(
        messages: List<JsonObject>,
        summaryLimit: Int,
    ): WorkSummarySections {
        val used = linkedSetOf<String>()
        val maxPerItem = (summaryLimit / 18).coerceIn(200, 800)

        fun select(
            role: String? = null,
            cues: Set<String>? = null,
            maxItems: Int,
        ): List<String> = messages.asReversed()
            .asSequence()
            .filter { role == null || it["role"].asText() == role }
            .mapNotNull(::messageText)
            .map(::normalize)
            .filter(String::isNotBlank)
            .filter { text -> cues == null || text.containsAnyCue(cues) }
            .filter { used.add(it) }
            .take(maxItems)
            .map { truncateWithoutSplittingSurrogatePair(it, maxPerItem) }
            .toList()
            .asReversed()

        // Prioritize the facts most likely to change future execution. Every row remains a direct
        // extract from model-visible history; classification only decides which heading owns it.
        val constraints = select(cues = WORK_CONSTRAINT_CUES, maxItems = 4)
        val failures = select(cues = WORK_FAILURE_CUES, maxItems = 4)
        val unfinished = select(cues = WORK_UNFINISHED_CUES, maxItems = 5)
        val decisions = select(role = "assistant", cues = WORK_DECISION_CUES, maxItems = 5)
        val goals = select(role = "user", maxItems = 6)
        val progress = select(role = "assistant", maxItems = 4)

        return WorkSummarySections(
            goals = goals,
            constraints = constraints,
            decisions = decisions,
            failures = failures,
            unfinished = unfinished,
            progress = progress,
        )
    }

    private fun String.containsAnyCue(cues: Set<String>): Boolean {
        val lower = lowercase()
        return cues.any { cue -> lower.contains(cue.lowercase()) }
    }

    private fun recentText(
        messages: List<JsonObject>,
        role: String,
        maxItems: Int,
        maxPerItem: Int,
    ): List<String> = messages.asReversed()
        .asSequence()
        .filter { it["role"].asText() == role }
        .mapNotNull(::messageText)
        .map(::normalize)
        .filter(String::isNotBlank)
        .distinct()
        .take(maxItems)
        .map { truncateWithoutSplittingSurrogatePair(it, maxPerItem) }
        .toList()
        .asReversed()

    private fun recentTools(messages: List<JsonObject>, maxItems: Int): List<String> =
        messages.asReversed()
            .asSequence()
            .flatMap { message ->
                val calls = message["tool_calls"] as? JsonArray ?: return@flatMap emptySequence()
                calls.asSequence().mapNotNull { raw ->
                    val call = raw as? JsonObject ?: return@mapNotNull null
                    val function = call["function"] as? JsonObject ?: return@mapNotNull null
                    function["name"].asText()?.takeIf(String::isNotBlank)
                }
            }
            .distinct()
            .take(maxItems)
            .toList()
            .asReversed()

    private fun messageText(message: JsonObject): String? = when (val content = message["content"]) {
        is JsonPrimitive -> content.contentOrNull
        is JsonArray -> content.joinToString("\n") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> part["text"].asText().orEmpty()
                else -> ""
            }
        }.takeIf(String::isNotBlank)
        else -> null
    }

    private fun normalize(value: String): String =
        value.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun kotlinx.serialization.json.JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private companion object {
        val WORK_CONSTRAINT_CUES = setOf(
            "必须", "禁止", "不能", "不要", "只允许", "仅限", "限制", "约束", "要求",
            "保持", "兼容", "边界", "must", "must not", "never", "only", "constraint",
        )
        val WORK_FAILURE_CUES = setOf(
            "失败", "报错", "错误", "异常", "超时", "冲突", "回退", "无法", "风险",
            "未通过", "failure", "failed", "error", "timeout", "conflict", "rollback", "risk",
        )
        val WORK_UNFINISHED_CUES = setOf(
            "下一步", "继续", "剩余", "未完成", "待处理", "后续", "还要", "尚未",
            "next", "remaining", "todo", "pending", "follow-up",
        )
        val WORK_DECISION_CUES = setOf(
            "决定", "确认", "采用", "改为", "保留", "结论", "方案", "选择", "完成",
            "decide", "confirmed", "adopt", "keep", "conclusion", "completed",
        )

        const val DEFAULT_MAX_HISTORY_CHARS = 500_000
        const val DEFAULT_TAIL_CHARS = 240_000
        const val DEFAULT_MAX_SUMMARY_CHARS = 16_000
    }
}
