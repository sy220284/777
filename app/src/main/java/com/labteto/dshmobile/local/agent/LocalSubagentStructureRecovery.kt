package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.model.modelFailureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun minimalSubagentRecoveryHistory(history: List<JsonObject>): List<JsonObject>? {
    val latestUserIndex = history.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "user"
    }
    if (latestUserIndex < 0) return null
    val prefix = history.take(latestUserIndex).filter { message ->
        message["role"]?.jsonPrimitive?.contentOrNull in setOf("system", "developer")
    }
    return prefix + history[latestUserIndex]
}

internal fun canRetrySubagentStructureFailure(
    error: Throwable,
    history: List<JsonObject>,
): Boolean {
    val modelError = error as? LocalModelException ?: return false
    val structural = modelError.code == "MODEL_HISTORY_INVALID" || modelError.status == 400
    if (!structural) return false
    val hasToolState = history.any { message ->
        val role = message["role"]?.jsonPrimitive?.contentOrNull
        role == "tool" ||
            (role == "assistant" && (message["tool_calls"] as? JsonArray)?.isNotEmpty() == true)
    }
    return !hasToolState && minimalSubagentRecoveryHistory(history) != null
}

internal object LocalSubagentStructureRecovery {
    fun recover(
        error: Throwable,
        history: List<JsonObject>,
        subagentId: String,
        step: Int,
        eventLog: LocalSessionEventLog,
    ): List<JsonObject>? {
        if (!canRetrySubagentStructureFailure(error, history)) return null
        val recovered = minimalSubagentRecoveryHistory(history)
            ?.takeIf { candidate -> candidate != history }
            ?: return null
        eventLog.append("subagent/history-recovery", buildJsonObject {
            put("agent_id", subagentId)
            put("step", step)
            put("trigger", (error as? LocalModelException)?.code ?: "HTTP_400")
            put("messages_before", history.size)
            put("messages_after", recovered.size)
        })
        return recovered
    }
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
