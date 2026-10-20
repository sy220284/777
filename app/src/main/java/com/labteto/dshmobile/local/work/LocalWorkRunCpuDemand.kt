package com.labteto.dshmobile.local.work

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** CPU demand follows real executing bodies; batch receipts may be delayed by another approval. */
internal fun workRunCpuDemand(binding: LocalWorkRunBinding) = combine(
    binding.state.map { state ->
        setOfNotNull(state.work.pendingQuestion?.callId, state.work.pendingApproval?.callId)
    }.distinctUntilChanged(),
    binding.executingToolCallIds,
) { waiting, executing -> workRunNeedsCpu(waiting, executing) }.distinctUntilChanged()

internal fun workRunNeedsCpu(waitingCallIds: Set<String>, executingCallIds: Set<String>): Boolean =
    waitingCallIds.isEmpty() || executingCallIds.any { it !in waitingCallIds }
