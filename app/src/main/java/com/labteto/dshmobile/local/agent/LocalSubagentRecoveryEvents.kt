package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalHistoryCompaction
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.modelFailureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun recordSubagentContinuation(
    continuation: JsonObject,
    modelError: LocalModelException,
    continuationIndex: Int,
    durableHistory: LocalModelHistoryBuffer?,
    eventLog: LocalSessionEventLog,
    subagentId: String,
    step: Int,
) {
    durableHistory?.append(continuation)
    eventLog.append("subagent/continuation-queued", buildJsonObject {
        put("agent_id", subagentId)
        put("step", step)
        put("continuation_index", continuationIndex)
        put("reason", modelError.code)
    })
}

internal fun recordSubagentCompaction(
    compaction: LocalHistoryCompaction,
    eventLog: LocalSessionEventLog,
    subagentId: String,
    round: Int,
) {
    eventLog.append("subagent/compaction", buildJsonObject {
        put("agent_id", subagentId)
        put("trigger", "context-overflow")
        put("round", round)
        put("omitted_messages", compaction.omittedMessages)
        put("summary", compaction.summary)
        put("estimated_tokens_before", compaction.estimatedTokensBefore)
        put("estimated_tokens_after", compaction.estimatedTokensAfter)
    })
}


internal fun logSubagentProviderError(
    eventLog: LocalSessionEventLog,
    subagentId: String,
    step: Int,
    error: LocalModelException,
) {
    eventLog.append("subagent/provider-error", buildJsonObject {
        put("agent_id", subagentId)
        put("step", step)
        put("code", error.code)
        error.status?.let { put("status", it) }
        error.providerRetryAfterMs?.let { put("retry_after_ms", it) }
        error.requestId?.let { put("request_id", it) }
        error.providerCode?.let { put("provider_code", it) }
        error.providerParam?.let { put("provider_param", it) }
        put("failure_kind", modelFailureKind(error))
        put("admission_state", error.admissionState.name.lowercase())
        put("continuation_eligible", error.continuationEligible)
        error.cause?.message?.takeIf(String::isNotBlank)?.let { put("cause_detail", it.take(800)) }
    })
}
