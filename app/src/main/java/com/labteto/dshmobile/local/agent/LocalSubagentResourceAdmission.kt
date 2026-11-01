package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uses the existing shared AGENT resource scheduler; no second semaphore or ownership store.
 * Recursive descendants have a bounded slot wait so ancestor leases cannot cause infinite
 * admission waits when a device has insufficient Agent capacity.
 */
internal suspend fun <T> withLocalSubagentAgentSlot(
    scheduler: HarnessResourceScheduler,
    owner: String,
    waitMillis: Long? = null,
    block: suspend () -> T,
): T {
    if (waitMillis == null) {
        return scheduler.withResource(HarnessResourceKind.AGENT, owner, block = block)
    }
    require(waitMillis in 1L..60_000L) { "SUBAGENT_AGENT_SLOT_WAIT_INVALID" }
    val lease = withTimeoutOrNull(waitMillis) {
        scheduler.acquire(HarnessResourceKind.AGENT, owner)
    } ?: error("NESTED_DELEGATION_NO_AGENT_SLOT")
    return try {
        block()
    } finally {
        lease.close()
    }
}
