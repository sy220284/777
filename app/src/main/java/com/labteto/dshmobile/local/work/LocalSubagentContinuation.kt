package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT = "subagent/history-checkpoint"
private const val LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION = 1
private const val MAX_CLAIMED_MESSAGE_IDS = 256

internal fun persistentSubagentId(backgroundJobId: String): String =
    "sa-" + backgroundJobId.removePrefix("job-").take(24)

internal data class LocalSubagentHistoryCheckpoint(
    val history: List<JsonObject>,
    val claimedMessageIds: Set<String>,
    val step: Int,
)

internal fun encodeLocalSubagentHistoryCheckpoint(
    backgroundJobId: String,
    agentId: String,
    step: Int,
    history: List<JsonObject>,
    claimedMessageIds: Set<String>,
): JsonObject = buildJsonObject {
    put("version", LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION)
    put("background_job_id", backgroundJobId)
    put("agent_id", agentId)
    put("step", step.coerceAtLeast(0))
    put("history", JsonArray(history))
    put(
        "claimed_message_ids",
        JsonArray(
            claimedMessageIds
                .toList()
                .takeLast(MAX_CLAIMED_MESSAGE_IDS)
                .map(::JsonPrimitive),
        ),
    )
}

internal fun decodeLocalSubagentHistoryCheckpoint(
    data: JsonObject,
): LocalSubagentHistoryCheckpoint? {
    val version = data["version"]?.jsonPrimitive?.intOrNull ?: return null
    if (version != LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION) return null
    val rawHistory = data["history"] as? JsonArray ?: return null
    val history = rawHistory.mapNotNull { it as? JsonObject }
    if (history.size != rawHistory.size) return null
    if (
        history.any { message ->
            message["role"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
        }
    ) {
        return null
    }
    val claimed = (data["claimed_message_ids"] as? JsonArray)
        ?.mapNotNull { element ->
            (element as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf(String::isNotBlank)
        }
        ?.takeLast(MAX_CLAIMED_MESSAGE_IDS)
        ?.toSet()
        .orEmpty()
    val step = data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
    return LocalSubagentHistoryCheckpoint(
        history = history,
        claimedMessageIds = claimed,
        step = step,
    )
}
