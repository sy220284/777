package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.QueuedAgentInput
import kotlinx.serialization.json.JsonObject
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
    val leadFirst = originalTeam.filter { item ->
        (item.agentSource?.get("sender_id") as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull == sessionId
    } + originalTeam.filterNot { item ->
        (item.agentSource?.get("sender_id") as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull == sessionId
    }
    return inputs.toMutableList().also { ordered ->
        positions.forEachIndexed { index, position ->
            ordered[position] = leadFirst[index]
        }
    }
}
