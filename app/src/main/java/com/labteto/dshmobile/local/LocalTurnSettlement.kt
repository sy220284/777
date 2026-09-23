package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentToolCall

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
