package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.resource.HarnessResourceSnapshot
import java.time.LocalDate
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.observability.AppLogEntry

/** Owns environment diagnostic snapshot selection and report assembly. */
internal class LocalEnvironmentInfoCoordinator(
    private val workspacePath: () -> String,
    private val resourceSnapshot: () -> HarnessResourceSnapshot,
    private val historyBudget: (LocalWorkRunBinding?) -> LocalHistoryBudget,
    private val requestPressureStore: LocalRequestPressureStore,
    private val usageTracker: DeepSeekUsageTracker,
    private val toolExecutionCoordinator: LocalToolExecutionCoordinator,
    private val commandAvailable: (String) -> Boolean,
    private val runtimeStatuses: () -> List<String>,
    private val diagnostics: () -> List<AppLogEntry>,
    private val storageStatus: () -> String,
    private val processExitStatus: () -> String,
    private val foregroundSessionId: () -> String,
    private val foregroundHistory: () -> LocalModelHistoryBuffer,
    private val foregroundPendingInputs: () -> Int,
    private val pendingInputLimit: Int,
    private val foregroundWorkBudget: (String) -> LocalWorkExecutionBudget.Snapshot?,
) {
    fun build(binding: LocalWorkRunBinding?): String {
        val sessionId = binding?.sessionId ?: foregroundSessionId()
        val history = binding?.modelHistory ?: foregroundHistory()
        val pendingInputCount = binding?.pendingInputs?.size() ?: foregroundPendingInputs()
        val latestRequest = usageTracker.analyticsSnapshot().recentRecords.firstOrNull { record ->
            record.reported && record.inputTokens > 0L && record.context.sessionId == sessionId
        }
        val enabledOptional = binding?.enabledOptionalTools?.let { tools ->
            synchronized(tools) { tools.toSet() }
        } ?: toolExecutionCoordinator.enabledOptionalSnapshot()
        val commands = COMMANDS.filter(commandAvailable)
        return LocalEnvironmentReport.build(
            workspacePath = workspacePath(),
            resources = resourceSnapshot(),
            contextChars = history.encodedChars,
            contextBudgetChars = historyBudget(binding).maxHistoryChars,
            requestPressure = requestPressureStore.latest(sessionId),
            workContextAssessment = requestPressureStore.workAssessment(sessionId),
            contextWindow = requestPressureStore.window(sessionId),
            workBudget = binding?.executionControl?.budget?.snapshot() ?: foregroundWorkBudget(sessionId),
            latestRequest = latestRequest,
            capabilitySummary = toolExecutionCoordinator.capabilitySummary(enabledOptional),
            pendingInputs = pendingInputCount,
            pendingInputLimit = pendingInputLimit,
            commands = commands,
            runtimeStatuses = runtimeStatuses(),
            recentDiagnostics = diagnostics(),
        ) + "\n" + buildTokenUsageSummary(sessionId) +
            "\n" + storageStatus() + "\n" + processExitStatus()
    }

    private fun buildTokenUsageSummary(sessionId: String): String {
        val analytics = usageTracker.analyticsSnapshot()
        val session = usageTracker.analyticsGroupDetail(
            kind = TokenUsageGroupKind.SESSION,
            key = sessionId,
            recordLimit = TOKEN_USAGE_RECENT_RECORDS,
        )
        val today = analytics.days.firstOrNull { bucket ->
            bucket.epochDay == LocalDate.now().toEpochDay()
        }
        return buildString {
            appendLine("Token 权威账本：TokenUsageAnalyticsStore/SQLite；usage/token_usage.jsonl 仅为旧账本迁移入口。")
            if (session == null) {
                appendLine("当前会话 Token：暂无已记录请求。")
            } else {
                appendLine("当前会话 Token：${formatAggregate(session.aggregate)}")
                val actions = session.actions
                    .sortedByDescending { it.aggregate.totalTokens }
                    .take(6)
                if (actions.isNotEmpty()) {
                    appendLine(
                        "当前会话动作：" + actions.joinToString("；") { item ->
                            "${item.action.name.lowercase()}=${item.aggregate.totalTokens}"
                        },
                    )
                }
                val recent = session.records.sortedByDescending(TokenUsageRecord::timestamp)
                    .take(TOKEN_USAGE_RECENT_RECORDS)
                if (recent.isNotEmpty()) {
                    appendLine("最近请求（结构化账本，禁止为统计重复扫描 Session JSONL）：")
                    recent.forEach { record ->
                        val prompt = record.promptBreakdown
                        append("- action=").append(record.context.action.name.lowercase())
                        record.context.step?.let { append(" step=").append(it) }
                        append(" input=").append(record.inputTokens)
                        append(" cache_hit=").append(record.cacheHitTokens)
                        append(" cache_miss=").append(record.cacheMissTokens)
                        append(" output=").append(record.outputTokens)
                        append(" reasoning=").append(record.reasoningTokens)
                        append(" prompt(history=").append(prompt.historyTokens)
                        append(", current_user=").append(prompt.currentUserTokens)
                        append(", tools=").append(prompt.toolDefinitionTokens)
                        append(", system=").append(
                            prompt.systemBaseTokens + prompt.personaStateTokens +
                                prompt.memoryRuleTokens + prompt.otherSystemTokens,
                        )
                        appendLine(")")
                    }
                }
            }
            if (today != null) {
                appendLine("今日 Chat：${formatAggregate(today.chat)}")
                appendLine("今日 Work：${formatAggregate(today.work)}")
                if (today.other.requestCount > 0 || today.other.unreportedRequestCount > 0) {
                    appendLine("今日其他：${formatAggregate(today.other)}")
                }
            }
        }.trimEnd()
    }

    private fun formatAggregate(value: TokenUsageAggregate): String =
        "requests=${value.requestCount} input=${value.inputTokens} " +
            "cache_hit=${value.cacheHitTokens} cache_miss=${value.cacheMissTokens} " +
            "output=${value.outputTokens} reasoning=${value.reasoningTokens} total=${value.totalTokens} " +
            "unreported=${value.unreportedRequestCount}"

    private companion object {
        const val TOKEN_USAGE_RECENT_RECORDS = 8
        val COMMANDS = listOf(
            "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "sed", "grep", "find",
            "git", "curl", "wget", "python3", "python", "node",
        )
    }
}
