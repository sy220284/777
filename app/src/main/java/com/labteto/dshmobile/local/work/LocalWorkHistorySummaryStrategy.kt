package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.context.isTrustedContextCheckpointModelMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import com.labteto.dshmobile.local.model.LocalCanonicalModelCodec
import com.labteto.dshmobile.local.model.truncateWithoutSplittingSurrogatePair

internal object LocalWorkHistorySummaryStrategy : com.labteto.dshmobile.local.model.LocalHistorySummaryProvider {
    override fun summarize(mode: com.labteto.dshmobile.local.model.LocalHistorySummaryMode,
        omitted: List<JsonObject>, fullHistory: List<JsonObject>, maxChars: Int,
        input: com.labteto.dshmobile.local.model.LocalHistorySummaryInput?): com.labteto.dshmobile.local.model.LocalRenderedHistorySummary {
        require(mode == com.labteto.dshmobile.local.model.LocalHistorySummaryMode.WORK) { "工作摘要策略不能解释其他功能" }
        return summarize(omitted, fullHistory, maxChars, input)
    }

    fun summarize(messages: List<JsonObject>, fullHistory: List<JsonObject>, summaryLimit: Int,
        input: com.labteto.dshmobile.local.model.LocalHistorySummaryInput?): com.labteto.dshmobile.local.model.LocalRenderedHistorySummary {
        require(input == null || input is LocalStructuredWorkState) { "工作摘要输入类型不匹配" }
        val checkpoint = buildWorkCheckpoint(messages, summaryLimit, input as? LocalStructuredWorkState,
            LocalWorkCheckpoint.latestFrom(fullHistory))
        val text = buildString {
                    val sections = requireNotNull(checkpoint) {
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
        val summary = truncateWithoutSplittingSurrogatePair(text, summaryLimit)
        val block = buildString {
            append("<compacted-summary>\n")
            append("较早工作历史已压缩为结构化检查点；字段均直接提取自原会话，当前工作区状态优先。")
            append("\n</compacted-summary>\n")
            append(checkpoint.toModelBlock())
        }
        return com.labteto.dshmobile.local.model.LocalRenderedHistorySummary(summary, block)
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
        val lastDecisionReset = extractionMessages.indexOfLast { message ->
            message["role"].asText() == "user" &&
                (messageText(message)?.let(::isExplicitWorkDecisionReset) == true)
        }
        val lastConstraintReset = extractionMessages.indexOfLast { message ->
            message["role"].asText() == "user" &&
                (messageText(message)?.let(::isExplicitWorkConstraintReset) == true)
        }

        fun select(
            role: String? = null,
            cues: Set<String>? = null,
            maxItems: Int,
            decisionsAfterResetOnly: Boolean = false,
            constraintsAfterResetOnly: Boolean = false,
            explicitDecisionOnly: Boolean = false,
        ): List<String> = (when {
            decisionsAfterResetOnly && lastDecisionReset >= 0 -> extractionMessages.drop(lastDecisionReset)
            constraintsAfterResetOnly && lastConstraintReset >= 0 -> extractionMessages.drop(lastConstraintReset)
            else -> extractionMessages
        }).asReversed()
            .asSequence()
            .filter { role == null || it["role"].asText() == role }
            .mapNotNull(::messageText)
            .map(::normalize)
            .filter(String::isNotBlank)
            .filter { text -> !explicitDecisionOnly || isExplicitUserWorkDecision(text) }
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
        val previousConstraintEpoch = previousCheckpoint?.constraintEpoch ?: 0L
        val observedConstraintEpoch = structuredWorkState?.constraintEpoch ?: 0L
        val constraintReset = observedConstraintEpoch > previousConstraintEpoch ||
            (observedConstraintEpoch == 0L && lastConstraintReset >= 0)
        val constraintEpoch = when {
            observedConstraintEpoch > previousConstraintEpoch -> observedConstraintEpoch
            constraintReset -> previousConstraintEpoch + 1L
            else -> previousConstraintEpoch
        }
        // A scoped edit is authoritative only if the user quoted the precise superseded
        // requirement. Preserve tombstones across compactions; do not revoke unrelated rules.
        val currentScopedEdits = extractionMessages
            .drop(if (lastConstraintReset >= 0) lastConstraintReset else 0)
            .asSequence()
            .filter { it["role"].asText() == "user" }
            .mapNotNull(::messageText)
            .mapNotNull(::parseLocalWorkScopedConstraintEdit)
            .toList()
        val revokedConstraints = (
            (if (constraintReset) emptyList() else previousCheckpoint?.revokedConstraints.orEmpty()) +
                structuredWorkState?.revokedConstraints.orEmpty() +
                currentScopedEdits.map(LocalWorkScopedConstraintEdit::previous)
            ).map(String::trim).filter(String::isNotBlank).distinct().takeLast(64)
        val explicitReplacements = currentScopedEdits.mapNotNull(LocalWorkScopedConstraintEdit::replacement)
        val constraints = mergeStructured(
            structuredWorkState?.constraints.orEmpty() +
                explicitReplacements +
                if (constraintReset) emptyList() else previousCheckpoint?.constraints.orEmpty(),
            if (constraintReset && lastConstraintReset < 0) emptyList() else
                select(role = "user", cues = WORK_CONSTRAINT_CUES, maxItems = 6,
                    constraintsAfterResetOnly = true)
                    .filterNot { isExplicitWorkConstraintReset(it) && !hasNewWorkConstraintAfterReset(it) }
                    .filterNot { parseLocalWorkScopedConstraintEdit(it) != null },
            maxItems = 16,
        ).filterNot { isRevokedWorkConstraint(it, revokedConstraints) }.takeLast(8)
        val failures = mergeStructured(
            structuredWorkState?.failures.orEmpty() + previousCheckpoint?.failures.orEmpty(),
            select(cues = WORK_FAILURE_CUES, maxItems = 4),
            maxItems = 6,
        )
        val previousDecisionEpoch = previousCheckpoint?.decisionEpoch ?: 0L
        val observedDecisionEpoch = structuredWorkState?.decisionEpoch ?: 0L
        val explicitReset = observedDecisionEpoch > previousDecisionEpoch ||
            (observedDecisionEpoch == 0L && lastDecisionReset >= 0)
        val decisionEpoch = when {
            observedDecisionEpoch > previousDecisionEpoch -> observedDecisionEpoch
            explicitReset -> previousDecisionEpoch + 1L
            else -> previousDecisionEpoch
        }
        val previousVerifiedDecisions = previousCheckpoint?.takeIf { it.userDecisionsVerified }?.decisions.orEmpty()
        val decisions = mergeStructured(
            structuredWorkState?.decisions.orEmpty() +
                if (explicitReset) emptyList() else previousVerifiedDecisions,
            // The reset can still be in the live causal tail while all pre-reset decisions
            // sit in the compacted region. In that case none of those fallback messages may
            // reinstate the revoked plan, even if the reset text is absent from this slice.
            if (explicitReset && lastDecisionReset < 0) emptyList() else
                select(role = "user", cues = WORK_DECISION_CUES, maxItems = 4,
                    decisionsAfterResetOnly = true, explicitDecisionOnly = true),
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
        val unverifiedPriorDecisions = if (!explicitReset && previousCheckpoint?.userDecisionsVerified == false) {
            previousCheckpoint.decisions.take(3).map { "历史未核验方案（仅供参考，不得当作用户确认）：$it" }
        } else emptyList()
        val progress = mergeStructured(
            structuredWorkState?.progress.orEmpty() + previousCheckpoint?.progress.orEmpty() +
                unverifiedPriorDecisions,
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
        // Tool retention must not parse human-readable "call=..." observations as authority.
        // Keep typed IDs through repeat compactions; legacy checkpoints remain readable.
        val legacyCallIds = if (previousCheckpoint?.hasTypedToolReferences == false) {
            previousCheckpoint.facts.mapNotNull { text ->
                Regex("""(?:^|\s)call=([^\s]+)""").find(text)?.groupValues?.getOrNull(1)
            }
        } else emptyList()
        val toolCallIds = (previousCheckpoint?.toolCallIds.orEmpty() +
            legacyCallIds + structuredWorkState?.toolCallIds.orEmpty())
            .filter(String::isNotBlank)
            .distinct()
            .takeLast(32)

        return LocalWorkCheckpoint(
            goals = goals,
            plan = plan,
            constraints = constraints,
            decisions = decisions,
            decisionEpoch = decisionEpoch,
            constraintEpoch = constraintEpoch,
            revokedConstraints = revokedConstraints,
            userDecisionsVerified = true,
            failures = failures,
            unfinished = unfinished,
            progress = progress,
            facts = facts,
            artifacts = artifacts,
            tools = tools,
            toolCallIds = toolCallIds,
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

    private val WORK_CONSTRAINT_CUES = setOf(
            "必须", "禁止", "不能", "不要", "只允许", "仅限", "限制", "约束", "要求",
            "保持", "兼容", "边界", "must", "must not", "never", "only", "constraint",
        )
    private val WORK_FAILURE_CUES = setOf(
            "失败", "报错", "错误", "异常", "超时", "冲突", "回退", "无法", "风险",
            "未通过", "failure", "failed", "error", "timeout", "conflict", "rollback", "risk",
        )
    private val WORK_UNFINISHED_CUES = setOf(
            "下一步", "继续", "剩余", "未完成", "待处理", "后续", "还要", "尚未",
            "next", "remaining", "todo", "pending", "follow-up",
        )
    private val WORK_DECISION_CUES = setOf(
            "决定", "确认", "采用", "改为", "保留", "结论", "方案", "选择", "完成",
            "decide", "confirmed", "adopt", "keep", "conclusion", "completed",
        )
    private val WORK_ARTIFACT_PATTERN = Regex(
            """(?:(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\.[A-Za-z0-9]{1,10}|https?://[^\s)\]}>"']+)""",
        )

}
