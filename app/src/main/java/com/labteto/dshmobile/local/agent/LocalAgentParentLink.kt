package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRunExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Parent identity is durable evidence, not a permission grant or second task owner. */
internal data class LocalAgentParentLink(
    val sessionId: String,
    val parentRunId: String,
    val parentSlotId: String,
    val parentToolCallId: String? = null,
) {
    init {
        require(sessionId.isNotBlank() && parentRunId.isNotBlank() && parentSlotId.isNotBlank()) {
            "AGENT_PARENT_LINK_INVALID"
        }
        require(parentToolCallId == null || parentToolCallId.isNotBlank()) {
            "AGENT_PARENT_TOOL_CALL_INVALID"
        }
    }
}

/** Capture the caller's true live owner; never infer it from the visible/global session. */
internal suspend fun captureLocalAgentParentLink(parentToolCallId: String? = null): LocalAgentParentLink? {
    val execution = currentCoroutineContext()[AgentRunExecutionContext] ?: return null
    execution.requireCurrent()
    return LocalAgentParentLink(
        sessionId = execution.identity.sessionId,
        parentRunId = execution.identity.runId,
        parentSlotId = execution.identity.slotId,
        parentToolCallId = parentToolCallId,
    )
}

internal fun LocalAgentParentLink.toJson(): JsonObject = buildJsonObject {
    put("version", 1)
    put("session_id", sessionId)
    put("parent_run_id", parentRunId)
    put("parent_slot_id", parentSlotId)
    parentToolCallId?.let { put("parent_tool_call_id", it) }
}

internal fun decodeLocalAgentParentLink(raw: JsonObject?): LocalAgentParentLink? {
    if (raw == null) return null
    require(raw["version"]?.jsonPrimitive?.contentOrNull == "1") { "AGENT_PARENT_LINK_VERSION_INVALID" }
    fun required(name: String) = raw[name]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: error("AGENT_PARENT_LINK_FIELD_MISSING:$name")
    return LocalAgentParentLink(
        sessionId = required("session_id"),
        parentRunId = required("parent_run_id"),
        parentSlotId = required("parent_slot_id"),
        parentToolCallId = raw["parent_tool_call_id"]?.jsonPrimitive?.contentOrNull,
    )
}
