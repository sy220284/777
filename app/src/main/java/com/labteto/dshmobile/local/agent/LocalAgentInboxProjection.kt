package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

/**
 * Canonical typed Inbox -> model history projection for Lead, live child and cold recovery.
 * Agent-originated messages retain their untrusted-source wrapper from durable admission.
 */
internal fun modelMessageForAgentInput(input: QueuedAgentInput): JsonObject =
    input.modelMessage ?: buildJsonObject {
        put("role", "user")
        put("content", input.content)
    }

/**
 * Claude-style Team peer coordination priority, applied only to a newly admitted batch.
 * Real user/continuation input remains in its durable queue position; Team messages
 * from this session's Lead precede peers, with FIFO preserved within each class.
 *
 * This is a presentation/consumption projection, not a new queue or permission source.
 * On-disk claims and checkpoint history must use the SAME ordered batch so recovery
 * cannot silently reconstruct a different model input sequence.
 *
 * Shutdown control needs a separately authenticated command and is intentionally
 * not inferred from agent-authored message text.
 */
internal fun orderLocalTeamInboxBatch(
    inputs: List<QueuedAgentInput>,
    sessionId: String,
): List<QueuedAgentInput> {
    if (inputs.size < 2) return inputs
    fun isTeamMessage(input: QueuedAgentInput): Boolean =
        input.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.AGENT_MESSAGE &&
            (input.agentSource?.get("team_id") as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull == sessionId
    val positions = inputs.indices.filter { isTeamMessage(inputs[it]) }
    if (positions.size < 2) return inputs
    val originalTeam = positions.map(inputs::get)
    fun fromLead(input: QueuedAgentInput) =
        (input.agentSource?.get("sender_id") as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull == sessionId
    fun shutdownRequest(input: QueuedAgentInput) =
        fromLead(input) &&
            (input.agentSource?.get("control_kind") as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull == "shutdown_request"
    // Authenticated shutdown request > Lead message > peer; FIFO within each rank.
    // Never classify model-authored text as a shutdown command.
    val prioritized = originalTeam.filter(::shutdownRequest) +
        originalTeam.filter { fromLead(it) && !shutdownRequest(it) } +
        originalTeam.filterNot(::fromLead)
    return inputs.toMutableList().also { ordered ->
        positions.forEachIndexed { index, position ->
            ordered[position] = prioritized[index]
        }
    }
}

// Trust only a provenance-tagged Team message that survived final model-input preparation.
// Exact JSON match is conservative: transformed, truncated or compacted messages remain unconfirmed.
internal fun submittedLocalTeamMessageIds(
    newlyClaimed: List<QueuedAgentInput>,
    modelInput: Iterable<JsonElement>,
): Set<String> = newlyClaimed.asSequence().filter { message ->
    if (message.kind != com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.AGENT_MESSAGE) return@filter false
    val metadataId = (message.agentSource?.get("message_id") as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull
    if (metadataId != message.id) return@filter false
    val expected = modelMessageForAgentInput(message)
    val content = (expected["content"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    // The trusted Team Runtime creates this unique envelope, not the agent-authored message body.
    content.startsWith("[代理协作数据；") &&
        content.contains("消息ID=${message.id}；") &&
        modelInput.any { it == expected }
}.map(QueuedAgentInput::id).toSet()
