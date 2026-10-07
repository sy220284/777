package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.TokenUsageGroupKind
import com.labteto.dshmobile.local.model.DeepSeekUsageTracker
import com.labteto.dshmobile.local.record
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.observability.AppLogEntry
import com.labteto.dshmobile.observability.sanitizeDiagnosticText
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

    val latestSequence = eventLog.latestSequence()
    val events = ArrayDeque<LocalSessionEventLog.Event>(MAX_DIAGNOSTIC_EVENTS)
    var cursor = Long.MAX_VALUE
    while (events.size < MAX_DIAGNOSTIC_EVENTS) {
        val page = eventLog.pageBeforeNewestFirst(
            sequenceExclusive = cursor,
            limit = minOf(EVENT_PAGE_SIZE, MAX_DIAGNOSTIC_EVENTS - events.size),
        )
        if (page.isEmpty()) break
        page.forEach { event -> events.addFirst(event) }
        val nextCursor = page.last().sequence
        if (nextCursor >= cursor) break
        cursor = nextCursor
    }
    val logDiagnostics = eventLog.diagnostics()
    appendLine(
        "持久事件：导出最近 ${events.size} 条；latest_sequence=$latestSequence；" +
            "malformed_rows=${logDiagnostics.malformedRows} " +
            "segment_read_failures=${logDiagnostics.segmentReadFailures} " +
            "archive_failures=${logDiagnostics.archiveFailures}",
    )
    events.forEach { event ->
        append("event seq=").append(event.sequence)
        append(" at=").append(event.createdAt)
        append(" feature=").append(event.type.substringBefore('/'))
        append(" type=").append(event.type)
        append(" payload_chars=").append(event.data.toString().length)
        (DIAGNOSTIC_EVENT_KEYS + DIAGNOSTIC_EVENT_TYPE_KEYS[event.type].orEmpty())
            .distinct()
            .forEach { key ->
            event.data[key]?.toString()?.let { value ->
                append(" ").append(key).append("=")
                append(
                    sanitizeDiagnosticText(value)
                        .replace("\n", " ")
                        .take(MAX_DIAGNOSTIC_FIELD_CHARS),
                )
            }
        }
        appendLine()
    }

    appendLine()
    appendLine("当前会话 Token 请求账本：")
    val detail = usageTracker.analyticsGroupDetail(
        TokenUsageGroupKind.SESSION,
        sessionId,
        recordLimit = MAX_DIAGNOSTIC_TOKEN_RECORDS,
    )
    if (detail == null) {
        appendLine("暂无已记录模型/工具 Token 请求。")
    } else {
        val aggregate = detail.aggregate
        appendLine(
            "汇总 request_count=${aggregate.requestCount} input=${aggregate.inputTokens} " +
                "cache_hit=${aggregate.cacheHitTokens} cache_miss=${aggregate.cacheMissTokens} " +
                "cache_write=${aggregate.cacheWriteTokens} " +
                "output=${aggregate.outputTokens} reasoning=${aggregate.reasoningTokens} " +
                "total=${aggregate.totalTokens} unreported=${aggregate.unreportedRequestCount} " +
                "estimated_cost_cny=${aggregate.estimatedCostCny}",
        )
        val records = detail.records.sortedBy { it.timestamp }.takeLast(MAX_DIAGNOSTIC_TOKEN_RECORDS)
        val totalRecordCount = aggregate.requestCount + aggregate.unreportedRequestCount
        appendLine("请求记录：导出 ${records.size}/$totalRecordCount")
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
            context.taskLabel?.let { append(" task_label_chars=").append(it.length) }
            append(" model=").append(record.model.take(160))
            route?.provider?.takeIf(String::isNotBlank)?.let { append(" provider=").append(it.take(80)) }
            route?.profileId?.takeIf(String::isNotBlank)?.let { append(" profile_id=").append(it.take(160)) }
            route?.authKind?.takeIf(String::isNotBlank)?.let { append(" auth_kind=").append(it.take(80)) }
            route?.protocol?.takeIf(String::isNotBlank)?.let { append(" protocol=").append(it.take(80)) }
            route?.fingerprint?.takeIf(String::isNotBlank)?.let {
                append(" reply_route_fingerprint=").append(it.take(160))
            }
            append(" reported=").append(record.reported)
            append(" input=").append(record.inputTokens)
            append(" cache_hit=").append(record.cacheHitTokens)
            append(" cache_miss=").append(record.cacheMissTokens)
            append(" cache_write=").append(record.cacheWriteTokens)
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
    "budget_settlement",
    "continuation_eligible",
    "request_id",
    "request_uid",
    "request_envelope_fingerprint",
    "provider_attempt_fingerprint",
    "header_seq",
    "recovery_round",
    "streaming",
    "message_surface_seq",
    "tool_surface_seq",
    "context_surface_seq",
    "message_digest",
    "context_digest",
    "tool_schema_digest",
    "message_surface_digest",
    "context_surface_digest",
    "message_surface_redacted",
    "context_surface_redacted",
    "route_fingerprint",
    "reply_route_fingerprint",
    "reported",
    "prompt_tokens",
    "cache_hit_tokens",
    "cache_miss_tokens",
    "cache_write_tokens",
    "completion_tokens",
    "reasoning_tokens",
    "total_tokens",
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
    "stream_total_chars",
    "stream_tail_truncated",
    "side_effect",
    "execution_id",
    "root_call_id",
    "parent_execution_id",
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
    "source_as_of_sequence",
    "source_checkpoint_sequence",
    "source_message_count",
    "result_message_count",
    "summary_digest",
    "as_of_sequence",
    "strategy",
    "tool_count",
    "model_context_window_tokens",
    "context_generation",
    "context_prefill_tokens",
    "context_prefill_source",
    "cache_series_generation",
    "cache_prefix_continuity",
    "cache_tool_surface_stable",
    "cache_message_prefix_stable",
    "cache_previous_message_count",
    "plan_mode",
    "temperature",
    "credential_binding_valid",
    "matches_selected_account",
    "plan_scope_granted",
    "resource_invoke_granted",
    "comparison_reusable_tokens",
    "cache_missed_tokens",
    "comparison_response_id",
    "response_id",
    "extra_request_tokens",
    "work_steady_state",
    "history_budget_tokens",
    "tail_budget_tokens",
    "round",
    "trigger",
    "access",
    "impact",
    "next_attempt",
    "delay_ms",
    "generation",
)

private val DIAGNOSTIC_EVENT_TYPE_KEYS = mapOf(
    "request/header" to listOf("tool_names"),
    "tool/call" to listOf("id", "name"),
    "tool/execution-started" to listOf(
        "id", "name", "execution_id", "root_call_id", "parent_execution_id",
    ),
    "tool/result" to listOf(
        "id", "name", "is_error", "error_code", "runtime_settlement", "retention",
    ),
    "turn/end" to listOf("steps", "messages"),
)

private const val EVENT_PAGE_SIZE = 200
private const val MAX_DIAGNOSTIC_APP_LOGS = 800
private const val MAX_DIAGNOSTIC_EVENTS = 3_000
private const val MAX_DIAGNOSTIC_TOKEN_RECORDS = 2_000
private const val MAX_DIAGNOSTIC_FIELD_CHARS = 320
