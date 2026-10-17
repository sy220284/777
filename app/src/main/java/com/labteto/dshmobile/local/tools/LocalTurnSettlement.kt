package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import com.labteto.dshmobile.local.session.LocalSessionEventLog

internal data class LocalPendingToolSettlement(
    val call: AgentToolCall,
    val started: Boolean,
    val verifiedReadOnly: Boolean,
    val executionId: String?,
)

/** In-process cancellation uses the same persisted admission facts as process-loss recovery. */
internal fun pendingToolSettlements(
    eventLog: LocalSessionEventLog,
    calls: List<AgentToolCall>,
    completedCallIds: Set<String>,
): List<LocalPendingToolSettlement> {
    val stepStart = eventLog.latest("step/start") ?: return emptyList()
    val ledger = ToolAttemptLedger()
    eventLog.forEachAfter(stepStart.sequence) { ledger.observe(it.type, it.data) }
    val attempts = ledger.snapshot().associateBy { it.id }
    return calls.filterNot { it.id in completedCallIds }.mapNotNull { call ->
        val attempt = attempts[call.id]
        if (attempt?.settled == true) return@mapNotNull null
        LocalPendingToolSettlement(call, attempt?.started == true,
            attempt?.verifiedReadOnly == true, attempt?.executionId)
    }
}
