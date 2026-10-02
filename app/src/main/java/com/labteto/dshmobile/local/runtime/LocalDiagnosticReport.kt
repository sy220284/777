package com.labteto.dshmobile.local

import com.labteto.dshmobile.observability.AppLogEntry
import java.util.ArrayDeque

/**
 * Adds session-scoped, structure-only diagnostics to the shareable process report.
 *
 * The SessionEventLog remains the complete durable source of truth. Export deliberately whitelists
 * operation metadata instead of copying arbitrary message/tool payloads into a shareable file.
 */
internal fun appendLocalDiagnosticDetails(
    baseReport: String,
    sessionId: String,
    eventLog: LocalSessionEventLog,
    usageTracker: DeepSeekUsageTracker,
    appLogs: List<AppLogEntry>,
): String = buildString {
    append(baseReport.trimEnd())
    appendLine()
    appendLine()
    appendLine("持久日志来源：")
    val logSources = appLogs
        .map { entry ->
            val version = entry.appVersion?.takeIf(String::isNotBlank) ?: "legacy/unknown"
            val code = entry.appVersionCode?.toString() ?: "?"
            val process = entry.processInstanceId?.takeIf(String::isNotBlank) ?: "legacy"
            "$version($code)@process:$process"
        }
        .distinct()
    appendLine(if (logSources.isEmpty()) "暂无" else logSources.joinToString("；"))
    appendLine("说明：报告头的应用版本表示当前导出版本；每条持久日志的真实来源版本/进程以上述来源标识为准。")
    appendLine("持久进程日志索引：")
    appLogs.takeLast(MAX_DIAGNOSTIC_APP_LOGS).forEach { entry ->
        append("app_log at=").append(entry.timestampMillis)
        append(" level=").append(entry.level)
        append(" tag=").append(entry.tag.take(64))
        append(" build=").append(entry.appVersion ?: "legacy/unknown")
        append(" version_code=").append(entry.appVersionCode ?: -1)
        append(" process=").append(entry.processInstanceId ?: "legacy")
        append(" process_started_at=").append(entry.processStartedAtMillis ?: -1L)
        entry.throwableType?.takeIf(String::isNotBlank)?.let { append(" throwable=").append(it.take(64)) }
        appendLine()
    }
    appendLine()
    appendLine("当前会话结构化追踪：")
    appendLine("session_id=$sessionId")
    appendLine("说明：完整事实仍保存在 SessionEventLog；本报告导出操作身份、状态、耗时、预算与安全字段，不复制任意消息/工具正文。")

    var totalEvents = 0
    val events = ArrayDeque<LocalSessionEventLog.Event>(MAX_DIAGNOSTIC_EVENTS)
    eventLog.events().forEach { event ->
        totalEvents += 1
        if (events.size >= MAX_DIAGNOSTIC_EVENTS) events.removeFirst()
        events.addLast(event)
    }
    appendLine("持久事件：导出 ${events.size}/$totalEvents")
    events.forEach { event ->
        append("event seq=").append(event.sequence)
        append(" at=").append(event.createdAt)
        append(" feature=").append(event.type.substringBefore('/'))
        append(" type=").append(event.type)
        append(" payload_chars=").append(event.data.toString().length)
        DIAGNOSTIC_EVENT_KEYS.forEach { key ->
            event.data[key]?.toString()?.let { value ->
                append(" ").append(key).append("=")
                append(value.replace("\n", " ").take(MAX_DIAGNOSTIC_FIELD_CHARS))
            }
        }
        appendLine()
    }

    appendLine()
    appendLine("当前会话 Token 请求账本：")
    val detail = usageTracker.analyticsGroupDetail(TokenUsageGroupKind.SESSION, sessionId)
    if (detail == null) {
        appendLine("暂无已记录模型/工具 Token 请求。")
    } else {
        val aggregate = detail.aggregate
        appendLine(
            "汇总 request_count=${aggregate.requestCount} input=${aggregate.inputTokens} " +
                "cache_hit=${aggregate.cacheHitTokens} cache_miss=${aggregate.cacheMissTokens} " +
                "output=${aggregate.outputTokens} reasoning=${aggregate.reasoningTokens} " +
                "total=${aggregate.totalTokens} unreported=${aggregate.unreportedRequestCount} " +
                "estimated_cost_cny=${aggregate.estimatedCostCny}",
        )
        val records = detail.records.sortedBy { it.timestamp }.takeLast(MAX_DIAGNOSTIC_TOKEN_RECORDS)
        appendLine("请求记录：导出 ${records.size}/${detail.records.size}")
        records.forEach { record ->
            val context = record.context
            val route = record.route
            val prompt = record.promptBreakdown
            append("token_request at=").append(record.timestamp)
            append(" request_id=").append(record.requestId.take(160))
            append(" action=").append(context.action.name.lowercase())
            append(" mode=").append(context.mode?.name?.lowercase() ?: "unknown")
            context.turnId?.let { append(" turn_id=").append(it.take(160)) }
            context.runId?.let { append(" run_id=").append(it.take(160)) }
            context.parentRunId?.let { append(" parent_run_id=").append(it.take(160)) }
            context.runKind?.let { append(" run_kind=").append(it.take(64)) }
            context.agentId?.let { append(" agent_id=").append(it.take(160)) }
            context.step?.let { append(" step=").append(it) }
            context.taskLabel?.let { append(" task=").append(it.replace("\n", " ").take(160)) }
            append(" model=").append(record.model.take(160))
            route?.provider?.takeIf(String::isNotBlank)?.let { append(" provider=").append(it.take(80)) }
            route?.profileId?.takeIf(String::isNotBlank)?.let { append(" profile_id=").append(it.take(160)) }
            route?.authKind?.takeIf(String::isNotBlank)?.let { append(" auth_kind=").append(it.take(80)) }
            route?.protocol?.takeIf(String::isNotBlank)?.let { append(" protocol=").append(it.take(80)) }
            route?.fingerprint?.takeIf(String::isNotBlank)?.let { append(" route_fingerprint=").append(it.take(160)) }
            append(" reported=").append(record.reported)
            append(" input=").append(record.inputTokens)
            append(" cache_hit=").append(record.cacheHitTokens)
            append(" cache_miss=").append(record.cacheMissTokens)
            append(" output=").append(record.outputTokens)
            append(" reasoning=").append(record.reasoningTokens)
            append(" total=").append(record.totalTokens)
            append(" estimated_cost_cny=").append(record.estimatedCostCny)
            append(" prompt_system_base=").append(prompt.systemBaseTokens)
            append(" prompt_persona=").append(prompt.personaStateTokens)
            append(" prompt_memory=").append(prompt.memoryRuleTokens)
            append(" prompt_history=").append(prompt.historyTokens)
            append(" prompt_current_user=").append(prompt.currentUserTokens)
            append(" prompt_tools=").append(prompt.toolDefinitionTokens)
            append(" prompt_other_system=").append(prompt.otherSystemTokens)
            appendLine()
        }
    }
}

private val DIAGNOSTIC_EVENT_KEYS = listOf(
    "run_id",
    "parent_run_id",
    "run_kind",
    "agent_id",
    "turn_id",
    "step",
    "attempt",
    "call_id",
    "tool_name",
    "status",
    "phase",
    "mode",
    "action",
    "reason",
    "code",
    "failure_kind",
    "origin",
    "admission_state",
    "continuation_eligible",
    "request_id",
    "provider_code",
    "provider_param",
    "model",
    "provider",
    "profile_id",
    "auth_kind",
    "protocol",
    "duration_ms",
    "retryable",
    "will_retry",
    "side_effect",
    "message_count",
    "source_message_count",
    "projected_message_count",
    "history_projected",
    "context_chars",
    "estimated_input_tokens",
    "estimated_input_tokens_before_projection",
    "operational_input_limit_tokens",
    "system_tokens_estimate",
    "history_tokens_estimate",
    "current_user_tokens_estimate",
    "tool_definition_tokens_estimate",
    "estimated_tokens_before",
    "estimated_tokens_after",
    "omitted_messages",
    "strategy",
    "tool_count",
    "next_attempt",
    "delay_ms",
    "generation",
)

private const val MAX_DIAGNOSTIC_APP_LOGS = 800
private const val MAX_DIAGNOSTIC_EVENTS = 3_000
private const val MAX_DIAGNOSTIC_TOKEN_RECORDS = 2_000
private const val MAX_DIAGNOSTIC_FIELD_CHARS = 320
