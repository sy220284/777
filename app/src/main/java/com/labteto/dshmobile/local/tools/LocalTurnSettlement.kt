package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal data class LocalPendingToolSettlement(
    val call: AgentToolCall,
    val started: Boolean,
)

/**
 * Returns assistant-declared tool calls that still need a model-visible settlement before the next
 * turn. Completed calls are omitted; calls that reached tool/start are marked as outcome-unknown.
 */
internal fun pendingToolSettlements(
    calls: List<AgentToolCall>,
    startedCallIds: Set<String>,
    completedCallIds: Set<String>,
): List<LocalPendingToolSettlement> =
    calls
        .filterNot { call -> call.id in completedCallIds }
        .map { call ->
            LocalPendingToolSettlement(
                call = call,
                started = call.id in startedCallIds,
            )
        }


/** Returns calls whose executor actually started after the current step boundary. */
internal fun startedToolCallIdsForActiveStep(
    eventLog: LocalSessionEventLog,
    calls: List<AgentToolCall>,
): Set<String> {
    val stepStartSequence = eventLog.latest("step/start")?.sequence ?: -1L
    return calls.mapNotNull { call ->
        val started = eventLog.latestMatching(setOf("tool/execution-started")) { data ->
            data["id"]?.jsonPrimitive?.contentOrNull == call.id
        }
        call.id.takeIf { started != null && started.sequence > stepStartSequence }
    }.toSet()
}
