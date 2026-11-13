package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.RecoveredToolResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Work's interrupted settlement retains the same source as its original tool/call. */
internal fun interruptedWorkToolEvent(result: RecoveredToolResult, sourceMessageId: String?, reason: String) =
    buildJsonObject {
        put("source_message_id", sourceMessageId)
        result.step?.let { put("step", it) }
        put("id", result.callId)
        result.executionId?.let { put("execution_id", it) }
        result.name?.let { put("name", it) }
        put("content", result.content)
        put("model_content", result.modelContent)
        put("is_error", true)
        put("error_code", result.code)
        put("retryable", result.code != SessionRecovery.TOOL_OUTCOME_UNKNOWN)
        put("side_effect", if (result.code == SessionRecovery.TOOL_OUTCOME_UNKNOWN) "possible" else "none")
        put("runtime_settlement", true)
        put("reason", reason)
    }
