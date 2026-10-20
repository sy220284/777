package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.tools.LocalToolActivity
import com.labteto.dshmobile.local.tools.LocalToolActivityPhase
import com.labteto.dshmobile.local.tools.LocalToolActivityProjectionRuntime
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Derive CPU demand from the real interaction and Tool owners, never from page visibility. */
internal fun workRunCpuDemand(
    binding: LocalWorkRunBinding,
    activity: LocalToolActivityProjectionRuntime,
    startedAfter: Long,
) = combine(
    binding.state.map { state ->
        setOfNotNull(state.work.pendingQuestion?.callId, state.work.pendingApproval?.callId)
    }.distinctUntilChanged(),
    binding.eventLog.changes,
) { waiting, _ ->
    if (waiting.isEmpty()) true else workRunNeedsCpu(
        waiting, activity.snapshot(binding.eventLog).state.activities, startedAfter,
    )
}.distinctUntilChanged()

internal fun workRunNeedsCpu(
    waitingCallIds: Set<String>,
    activities: List<LocalToolActivity>,
    startedAfter: Long,
): Boolean = waitingCallIds.isEmpty() || activities.any {
    it.phase == LocalToolActivityPhase.RUNNING && it.callId !in waitingCallIds &&
        (it.declaredSequence ?: -1L) > startedAfter
}
