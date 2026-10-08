package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT = "subagent/history-checkpoint"
internal const val LOCAL_SUBAGENT_INBOX_CLAIM_EVENT = "subagent/inbox-claimed"
private const val LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION = 2
private const val LOCAL_SUBAGENT_INBOX_CLAIM_VERSION = 1
private const val MAX_CLAIMED_MESSAGE_IDS = 256
private const val MAX_TERMINAL_OUTPUT_CHARS = 65_536

internal fun localPersistentSubagentId(jobId: String): String =
    "sa-" + jobId.removePrefix("job-").take(24)

internal data class LocalSubagentHistoryCheckpoint(
    val history: List<JsonObject>,
    val claimedMessageIds: Set<String>,
    val step: Int,
    val softStepLimit: Int? = null,
    val terminalOutput: String? = null,
    val enabledOptionalTools: Set<String> = emptySet(),
)

internal fun encodeLocalSubagentHistoryCheckpoint(
    backgroundJobId: String,
    agentId: String,
    step: Int,
    history: List<JsonObject>,
    claimedMessageIds: Set<String>,
    softStepLimit: Int? = null,
    terminalOutput: String? = null,
    resultId: String? = null,
    resultFirst: Boolean = true,
    enabledOptionalTools: Set<String> = emptySet(),
): JsonObject = buildJsonObject {
    put("version", LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION)
    put("background_job_id", backgroundJobId)
    put("agent_id", agentId)
    put("step", step.coerceAtLeast(0))
    softStepLimit?.takeIf { it > 0 }?.let { put("soft_step_limit", it) }
    put("history", JsonArray(history))
    put("enabled_optional_tools", JsonArray(enabledOptionalTools.sorted().take(48).map(::JsonPrimitive)))
    terminalOutput?.takeIf(String::isNotBlank)?.let {
        put("terminal_output", it.takeLast(MAX_TERMINAL_OUTPUT_CHARS))
        resultId?.let { id -> put("result_id", id); put("result_first", resultFirst) }
    }
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

internal fun encodeLocalSubagentInboxClaimEvent(
    agentId: String,
    backgroundJobId: String,
    messages: List<QueuedAgentInput>,
): JsonObject = buildJsonObject {
    put("version", LOCAL_SUBAGENT_INBOX_CLAIM_VERSION)
    put("agent_id", agentId)
    put("background_job_id", backgroundJobId)
    put(
        "messages",
        JsonArray(
            messages.map { message ->
                buildJsonObject {
                    put("id", message.id)
                    put("content", message.content)
                    put("memory_input", message.memoryInput)
                    message.modelMessage?.let { put("model_message", it) }
                }
            },
        ),
    )
}

internal fun decodeLocalSubagentInboxClaimedMessages(
    data: JsonObject,
): List<QueuedAgentInput>? {
    val version = data["version"]?.jsonPrimitive?.intOrNull ?: return null
    if (version != LOCAL_SUBAGENT_INBOX_CLAIM_VERSION) return null
    val rawMessages = data["messages"] as? JsonArray ?: return null
    val decoded = rawMessages.mapNotNull { raw ->
        val message = raw as? JsonObject ?: return@mapNotNull null
        val id = message["id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val content = message["content"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        QueuedAgentInput(
            id = id,
            content = content,
            memoryInput = message["memory_input"]?.jsonPrimitive?.contentOrNull ?: content,
            modelMessage = message["model_message"] as? JsonObject,
        )
    }
    return decoded.takeIf { it.size == rawMessages.size }
}

internal fun shouldSettleCompletedSubagentCheckpoint(
    checkpoint: LocalSubagentHistoryCheckpoint?,
    pendingMessageIds: Set<String>,
): Boolean {
    if (checkpoint?.terminalOutput.isNullOrBlank()) return false
    return pendingMessageIds.none { messageId ->
        messageId !in checkpoint?.claimedMessageIds.orEmpty()
    }
}

internal fun shouldColdResumeCompletedSubagent(
    checkpoint: LocalSubagentHistoryCheckpoint?,
    pendingMessageIds: Set<String>,
): Boolean =
    !checkpoint?.terminalOutput.isNullOrBlank() &&
        pendingMessageIds.any { messageId ->
            messageId !in checkpoint?.claimedMessageIds.orEmpty()
        }

internal fun resolveSubagentTotalBudgetLimit(
    adaptiveStepLimit: Int,
    recoveredStep: Int,
    recoveredSoftStepLimit: Int?,
    resumeAfterCompletion: Boolean,
    maxDynamicSteps: Int,
): Int {
    val safeRecoveredStep = recoveredStep.coerceAtLeast(0)
    val safeAdaptiveLimit = adaptiveStepLimit.coerceAtLeast(1)
    val safeMax = maxDynamicSteps.coerceAtLeast(1)
    return if (resumeAfterCompletion) {
        (safeRecoveredStep + safeAdaptiveLimit).coerceAtMost(safeMax)
    } else {
        maxOf(safeAdaptiveLimit, recoveredSoftStepLimit ?: 0).coerceAtMost(safeMax)
    }
}

internal fun decodeLocalSubagentHistoryCheckpoint(
    data: JsonObject,
): LocalSubagentHistoryCheckpoint? {
    val version = data["version"]?.jsonPrimitive?.intOrNull ?: return null
    if (version !in 1..LOCAL_SUBAGENT_HISTORY_CHECKPOINT_VERSION) return null
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
    val rawEnabledTools = if (version >= 2) {
        data["enabled_optional_tools"] as? JsonArray ?: return null
    } else {
        null
    }
    val enabledTools = rawEnabledTools?.map { element ->
        val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        name?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
    }?.takeIf { it.size <= 48 }?.toSet()
        ?: if (version == 1) emptySet() else return null
    return LocalSubagentHistoryCheckpoint(
        history = history,
        claimedMessageIds = claimed,
        step = data["step"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0,
        softStepLimit = data["soft_step_limit"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it > 0 },
        terminalOutput = data["terminal_output"]?.jsonPrimitive?.contentOrNull,
        enabledOptionalTools = enabledTools,
    )
}
