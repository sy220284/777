package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class LocalHistoryCompaction(
    val messages: List<JsonObject>,
    val omittedMessages: Int,
    val summary: String,
    val workCheckpoint: LocalWorkCheckpoint? = null,
    val estimatedTokensBefore: Int = 0,
    val estimatedTokensAfter: Int = 0,
)

internal data class LocalStructuredWorkState(
    val goals: List<String> = emptyList(),
    val plan: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val unfinished: List<String> = emptyList(),
    val progress: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
)

internal data class LocalWorkCheckpoint(
    val goals: List<String>,
    val plan: List<String> = emptyList(),
    val constraints: List<String>,
    val decisions: List<String>,
    val failures: List<String>,
    val unfinished: List<String>,
    val progress: List<String>,
    val facts: List<String> = emptyList(),
    val artifacts: List<String>,
    val tools: List<String>,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", 2)
        put("goals", JsonArray(goals.map(::JsonPrimitive)))
        put("plan", JsonArray(plan.map(::JsonPrimitive)))
        put("constraints", JsonArray(constraints.map(::JsonPrimitive)))
        put("decisions", JsonArray(decisions.map(::JsonPrimitive)))
        put("failures", JsonArray(failures.map(::JsonPrimitive)))
        put("unfinished", JsonArray(unfinished.map(::JsonPrimitive)))
        put("progress", JsonArray(progress.map(::JsonPrimitive)))
        put("facts", JsonArray(facts.map(::JsonPrimitive)))
        put("artifacts", JsonArray(artifacts.map(::JsonPrimitive)))
        put("tools", JsonArray(tools.map(::JsonPrimitive)))
    }

    fun toModelBlock(): String = buildString {
        append("<work-checkpoint>\n")
        append(toJsonObject().toString())
        append("\n</work-checkpoint>")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun latestFrom(messages: List<JsonObject>): LocalWorkCheckpoint? =
            messages.asReversed().firstNotNullOfOrNull { message ->
                if (!isTrustedContextCheckpointModelMessage(message, LocalContextCheckpointKind.WORK)) {
                    return@firstNotNullOfOrNull null
                }
                val content = (message["content"] as? JsonPrimitive)?.contentOrNull ?: return@firstNotNullOfOrNull null
                // Trusted checkpoint messages always have one outer wrapper emitted by us.
                // Payload fields may legitimately contain the same literal text, so the outer
                // opening tag must be the first occurrence while the closing tag stays the last.
                val start = content.indexOf("<work-checkpoint>")
                val end = content.lastIndexOf("</work-checkpoint>")
                if (start < 0 || end <= start) return@firstNotNullOfOrNull null
                val payload = content
                    .substring(start + "<work-checkpoint>".length, end)
                    .trim()
                runCatching {
                    fromJsonObject(json.parseToJsonElement(payload) as JsonObject)
                }.getOrNull()
            }

        private fun fromJsonObject(value: JsonObject): LocalWorkCheckpoint {
            fun list(key: String): List<String> =
                (value[key] as? JsonArray).orEmpty()
                    .mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
                    .filter(String::isNotBlank)
            return LocalWorkCheckpoint(
                goals = list("goals"),
                plan = list("plan"),
                constraints = list("constraints"),
                decisions = list("decisions"),
                failures = list("failures"),
                unfinished = list("unfinished"),
                progress = list("progress"),
                facts = list("facts"),
                artifacts = list("artifacts"),
                tools = list("tools"),
            )
        }
    }
}

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
        structuredWorkState: LocalStructuredWorkState? = null,
    ): LocalHistoryCompaction? {
        val source = durableModelHistorySnapshot(history)
        val previousWorkCheckpoint = if (summaryMode == LocalHistorySummaryMode.WORK) {
            LocalWorkCheckpoint.latestFrom(source)
        } else {
            null
        }
        val sourceChanged = source !== history
        val effectiveMaxHistoryChars = budget?.maxHistoryChars ?: maxHistoryChars
        val effectiveTailChars = budget?.tailChars ?: tailChars
        val effectiveSummaryChars = budget?.maxSummaryChars ?: maxSummaryChars
        val effectiveMaxHistoryTokens = budget?.maxHistoryTokens
        val effectiveTailTokens = budget?.tailTokens
        val encodedChars = if (sourceChanged) source.sumOf { it.toString().length }
            else currentChars ?: source.sumOf { it.toString().length }
        val encodedTokens = if (sourceChanged) source.sumOf { estimateModelTokens(it.toString()) }
            else currentTokens ?: source.sumOf { estimateModelTokens(it.toString()) }
        val extraTokenRatio = effectiveMaxHistoryTokens?.takeIf { it > 0 }?.let {
            extraTokens.toDouble() / it.toDouble()
        } ?: 0.0
        val historyDepth = (source.size.toDouble() / 80.0).coerceIn(0.0, 1.0)
        val triggerRatio = if (budget?.adaptiveCompactionTrigger == false) {
            1.0
        } else {
            (0.92 - historyDepth * 0.08 - extraTokenRatio.coerceIn(0.0, 0.5) * 0.28)
                .coerceIn(0.68, 0.92)
        }
        val charPressure = encodedChars > (effectiveMaxHistoryChars * triggerRatio).toInt()
        val tokenPressure = effectiveMaxHistoryTokens?.let {
            encodedTokens + extraTokens > (it * triggerRatio).toInt()
        } == true
        if (source.size < 3 || (!charPressure && !tokenPressure)) return null

        val leadingSystemCount = source.takeWhile { it["role"].asText() == "system" }.size
        val firstBodyIndex = maxOf(1, leadingSystemCount)
        if (firstBodyIndex >= source.lastIndex) return null

        var start = firstBodyIndex
        var keptChars = 0
        var keptTokens = 0
        for (index in source.lastIndex downTo firstBodyIndex) {
            val encoded = source[index].toString()
            keptChars += encoded.length
            keptTokens += estimateModelTokens(encoded)
            if (
                keptChars > effectiveTailChars ||
                (effectiveTailTokens != null && keptTokens > effectiveTailTokens)
            ) {
                start = (index until source.size).firstOrNull { candidate ->
                    source[candidate]["role"].asText() == "user"
                } ?: index
                break
            }
        }
        // A tail can begin inside a large tool batch. Keep its assistant call and every result
        // together; cutting only by message size would manufacture an orphan tool result.
        if (source.getOrNull(start)?.get("role").asText() == "tool") {
            while (start > firstBodyIndex && source[start - 1]["role"].asText() == "tool") start -= 1
            if (
                start > firstBodyIndex &&
                source[start - 1]["role"].asText() == "assistant"
            ) {
                start -= 1
            }
        }
        if (start <= firstBodyIndex || start >= source.size) return null

        val latestTailSystemIndex = (source.lastIndex downTo firstBodyIndex)
            .firstOrNull { source[it]["role"].asText() == "system" }
        val protectedTailSystem = latestTailSystemIndex
            ?.takeIf { it < start }
            ?.let(source::get)
        val omitted = source.subList(firstBodyIndex, start).filterIndexed { offset, _ ->
            firstBodyIndex + offset != latestTailSystemIndex
        }
        if (omitted.isEmpty()) return null
        val workCheckpoint = if (summaryMode == LocalHistorySummaryMode.WORK) {
            buildWorkCheckpoint(
                omitted,
                effectiveSummaryChars,
                structuredWorkState,
                previousWorkCheckpoint,
            )
        } else {
            null
        }
        val summary = buildSummary(omitted, effectiveSummaryChars, summaryMode, workCheckpoint)
        val compacted = buildList {
            addAll(source.take(firstBodyIndex))
            add(
                buildTrustedContextCheckpointModelMessage(summaryMode,
                    if (workCheckpoint != null) {
                        buildString {
                            append("<compacted-summary>\n")
                            append("较早工作历史已压缩为结构化检查点；字段均直接提取自原会话，当前工作区状态优先。")
                            append("\n</compacted-summary>\n")
                            append(workCheckpoint.toModelBlock())
                        }
                    } else {
                        "<compacted-summary>\n$summary\n</compacted-summary>"
                    },
                ),
            )
            protectedTailSystem?.let(::add)
            addAll(source.drop(start).filterNot { isTrustedContextCheckpointModelMessage(it) })
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
            workCheckpoint = workCheckpoint,
            estimatedTokensBefore = estimatedTokensBefore,
            estimatedTokensAfter = estimatedTokensAfter,
        )
    }

    fun compactForOverflow(
        history: List<JsonObject>,
        summaryMode: LocalHistorySummaryMode = LocalHistorySummaryMode.WORK,
        structuredWorkState: LocalStructuredWorkState? = null,
    ): LocalHistoryCompaction? {
        val source = durableModelHistorySnapshot(history)
        if (source.size < 3) return null

        // Preserve the leading system prefix exactly. Request-only context such as persona/profile
        // facts is injected immediately after the base system prompt and must survive emergency
        // recovery. A later system message (for example the current chat turn's dynamic context)
        // stays at its original tail boundary instead of being hoisted ahead of retained history.
        val leadingSystemCount = source.takeWhile { it["role"].asText() == "system" }.size
        val protectedHead = source.take(leadingSystemCount)
        val firstBodyIndex = leadingSystemCount
        if (firstBodyIndex >= source.lastIndex) return null

        val latestTailSystemIndex = (source.lastIndex downTo firstBodyIndex)
            .firstOrNull { source[it]["role"].asText() == "system" }
        val compactableEndExclusive = latestTailSystemIndex ?: source.size
        val compactableBody = source.subList(firstBodyIndex, compactableEndExclusive)
        if (compactableBody.size < 2) return null

        val leadingSystem = protectedHead.firstOrNull() ?: buildJsonObject {
            put("role", "system")
            put("content", "")
        }
        val protectedSuffix = latestTailSystemIndex?.let { source.subList(it, source.size) }.orEmpty()
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
        // Emergency overflow recovery follows the same first step as normal context governance:
        // shrink stale tool payloads before discarding older semantic history.
        val projection = projectStaleToolResults(working, overflowBudget)
        val projectedWorking = projection?.messages ?: working
        val projectedChars = if (projection == null) encodedChars else projectedWorking.sumOf { it.toString().length }
        val projectedTokens = projection?.estimatedTokensAfter ?: encodedTokens
        val compacted = compact(
            history = projectedWorking,
            budget = overflowBudget,
            currentChars = projectedChars,
            currentTokens = projectedTokens,
            extraTokens = protectedTokens,
            summaryMode = summaryMode,
            structuredWorkState = structuredWorkState,
        )
        val recovered = compacted ?: projection ?: return null

        val rebuilt = buildList {
            addAll(protectedHead)
            addAll(recovered.messages.drop(1))
            addAll(protectedSuffix)
        }
        val before = history.sumOf { estimateModelTokens(it.toString()) }
        val after = rebuilt.sumOf { estimateModelTokens(it.toString()) }
        if (after >= before) return null
        return recovered.copy(
            messages = rebuilt,
            estimatedTokensBefore = before,
            estimatedTokensAfter = after,
        )
    }

    private fun buildSummary(
        messages: List<JsonObject>,
        summaryLimit: Int,
        summaryMode: LocalHistorySummaryMode,
        workCheckpoint: LocalWorkCheckpoint?,
    ): String {
        val user = recentText(
            messages.filterNot { isTrustedContextCheckpointModelMessage(it) },
            "user",
            maxItems = 8,
            maxPerItem = 1_200,
        )

        val text = buildString {
            when (summaryMode) {
                LocalHistorySummaryMode.WORK -> {
                    val sections = requireNotNull(workCheckpoint) {
                        "WORK 摘要必须携带结构化检查点"
                    }
                    append("较早工作检查点，共折叠 ")
                    append(messages.size)
                    append(" 条模型消息；内容均提取自原会话，实时目标、计划、任务和工作区状态优先。")
                    appendSummarySection("目标与需求", sections.goals)
                    appendSummarySection("当前计划", sections.plan)
                    appendSummarySection("约束与边界", sections.constraints)
                    appendSummarySection("关键决定与阶段结论", sections.decisions)
                    appendSummarySection("失败尝试与风险", sections.failures)
                    appendSummarySection("未完成事项", sections.unfinished)
                    appendSummarySection("其他阶段进展", sections.progress)
                    appendSummarySection("结构化执行事实", sections.facts)
                    if (sections.artifacts.isNotEmpty()) {
                        append("\n\n重要产物：")
                        sections.artifacts.forEach { append("\n- ").append(it) }
                    }
                    if (sections.tools.isNotEmpty()) {
                        append("\n\n已涉及工具：")
                        append(sections.tools.joinToString("、"))
                    }
                }
                LocalHistorySummaryMode.CHAT -> {
                    append("较早聊天连续性检查点，共折叠 ")
                    append(messages.size)
                    append(" 条模型消息，仅用于承接，不主动复述。")
                    if (user.isNotEmpty()) {
                        append("\n\n较早用户表达与事件：")
                        user.forEach { append("\n- ").append(it) }
                    }
                    append("\n\n当前人设、关系、长期记忆和近期原始对话优先。")
                }
            }
        }
        return truncateWithoutSplittingSurrogatePair(text, summaryLimit)
    }

    private fun StringBuilder.appendSummarySection(title: String, values: List<String>) {
        if (values.isEmpty()) return
        append("\n\n").append(title).append("：")
        values.forEach { append("\n- ").append(it) }
    }

    private fun buildWorkCheckpoint(
        messages: List<JsonObject>,
        summaryLimit: Int,
        structuredWorkState: LocalStructuredWorkState?,
        previousCheckpoint: LocalWorkCheckpoint?,
    ): LocalWorkCheckpoint {
        val used = linkedSetOf<String>()
        val maxPerItem = (summaryLimit / 48).coerceIn(100, 360)
        // A previous trusted checkpoint is already carried forward structurally through
        // previousCheckpoint. Re-extracting its serialized model block as ordinary user text
        // nests <work-checkpoint> markers inside the next checkpoint and corrupts subsequent
        // parsing while also wasting context.
        val extractionMessages = messages.filterNot { isTrustedContextCheckpointModelMessage(it) }

        fun select(
            role: String? = null,
            cues: Set<String>? = null,
            maxItems: Int,
        ): List<String> = extractionMessages.asReversed()
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

        fun mergeStructured(
            structured: List<String>,
            fallback: List<String>,
            maxItems: Int,
        ): List<String> = (structured + fallback)
            .map(::normalize)
            .filter(String::isNotBlank)
            .distinct()
            .take(maxItems)
            .map { truncateWithoutSplittingSurrogatePair(it, maxPerItem) }

        // Runtime-owned active work state is authoritative. A trusted previous checkpoint is then
        // carried forward so repeated compaction cannot silently forget still-valid constraints,
        // decisions or known failed approaches. Keyword extraction remains a bounded fallback for
        // history that predates the structured state.
        val constraints = mergeStructured(
            structuredWorkState?.constraints.orEmpty() + previousCheckpoint?.constraints.orEmpty(),
            select(cues = WORK_CONSTRAINT_CUES, maxItems = 6),
            maxItems = 8,
        )
        val failures = mergeStructured(
            structuredWorkState?.failures.orEmpty() + previousCheckpoint?.failures.orEmpty(),
            select(cues = WORK_FAILURE_CUES, maxItems = 4),
            maxItems = 6,
        )
        val decisions = mergeStructured(
            structuredWorkState?.decisions.orEmpty() + previousCheckpoint?.decisions.orEmpty(),
            select(role = "assistant", cues = WORK_DECISION_CUES, maxItems = 4),
            maxItems = 6,
        )
        // Reserve explicit unfinished work before the broad user-goal fallback. Otherwise a
        // "下一步/继续" message is consumed as a generic goal by the shared de-dup set.
        val unfinishedFallback = select(cues = WORK_UNFINISHED_CUES, maxItems = 4)
        val goals = mergeStructured(
            structuredWorkState?.goals.orEmpty() + previousCheckpoint?.goals.orEmpty(),
            select(role = "user", maxItems = 4),
            maxItems = 5,
        )
        val plan = mergeStructured(
            structuredWorkState?.plan.orEmpty() + previousCheckpoint?.plan.orEmpty(),
            emptyList(),
            maxItems = 8,
        )
        val unfinished = mergeStructured(
            structuredWorkState?.unfinished.orEmpty() + previousCheckpoint?.unfinished.orEmpty(),
            unfinishedFallback,
            maxItems = 8,
        )
        val progress = mergeStructured(
            structuredWorkState?.progress.orEmpty() + previousCheckpoint?.progress.orEmpty(),
            select(role = "assistant", maxItems = 3),
            maxItems = 8,
        )
        val facts = mergeStructured(
            structuredWorkState?.facts.orEmpty() + previousCheckpoint?.facts.orEmpty(),
            emptyList(),
            maxItems = 10,
        )
        val extractedArtifacts = extractionMessages.asReversed()
            .asSequence()
            .mapNotNull(::messageText)
            .flatMap { text -> WORK_ARTIFACT_PATTERN.findAll(text).map { match ->
                    truncateWithoutSplittingSurrogatePair(
                        match.value.trimEnd('.', ',', ';', ':'),
                        500,
                    )
                } }
            .filter(String::isNotBlank)
            .distinct()
            .take(6)
            .toList()
            .asReversed()
        val artifacts = mergeStructured(
            structuredWorkState?.artifacts.orEmpty() + previousCheckpoint?.artifacts.orEmpty(),
            extractedArtifacts,
            maxItems = 8,
        )
        val tools = mergeStructured(
            structuredWorkState?.tools.orEmpty() + previousCheckpoint?.tools.orEmpty(),
            recentTools(extractionMessages, maxItems = 8),
            maxItems = 12,
        )

        return LocalWorkCheckpoint(
            goals = goals,
            plan = plan,
            constraints = constraints,
            decisions = decisions,
            failures = failures,
            unfinished = unfinished,
            progress = progress,
            facts = facts,
            artifacts = artifacts,
            tools = tools,
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
                LocalCanonicalModelCodec.diagnosticToolNames(message).asSequence()
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
        val WORK_ARTIFACT_PATTERN = Regex(
            """(?:(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,10}|https?://[^\s)\]}>"']+)""",
        )

        const val DEFAULT_MAX_HISTORY_CHARS = 500_000
        const val DEFAULT_TAIL_CHARS = 240_000
        const val DEFAULT_MAX_SUMMARY_CHARS = 16_000
    }
}
