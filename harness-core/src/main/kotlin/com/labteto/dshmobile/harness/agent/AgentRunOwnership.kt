package com.labteto.dshmobile.harness.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException

/** A slot is a scheduling identity, independent of product Feature semantics. */
data class AgentRunIdentity(val sessionId: String, val slotId: String, val runId: String)

/**
 * Owns live run submission rights. Check and durable commit share the same lock as handoff;
 * a replacement run cannot enter between validation and the old run's checkpoint append.
 * Persistence runs first, so a failed claim never steals the previous owner's authority.
 */
class AgentRunOwnership {
    private class Slot {
        var owner: AgentRunIdentity? = null
        var reservations = 0
    }
    private val slots = mutableMapOf<Pair<String, String>, Slot>()

    fun executionContext(identity: AgentRunIdentity, parentRunId: String? = null) =
        AgentRunExecutionContext(identity, parentRunId, this)

    fun claim(identity: AgentRunIdentity, persist: () -> Unit) = withSlot(identity) { slot ->
        persist()
        slot.owner = identity
    }

    fun isCurrent(identity: AgentRunIdentity): Boolean = withSlot(identity) { it.owner == identity }

    fun commit(identity: AgentRunIdentity, terminal: Boolean = false, persist: () -> Unit): Boolean =
        withSlot(identity) { slot ->
            if (slot.owner != identity) return@withSlot false
            persist()
            if (terminal) slot.owner = null
            true
        }

    /** A cold recovery has no live owner; a different live run can never be retired by it. */
    fun retire(identity: AgentRunIdentity, persist: () -> Boolean): Boolean = withSlot(identity) { slot ->
        if (slot.owner != null && slot.owner != identity) return@withSlot false
        if (!persist()) return@withSlot false
        slot.owner = null
        true
    }

    /** Session-local IO must never hold the registry monitor or stall unrelated run slots. */
    private fun <T> withSlot(identity: AgentRunIdentity, block: (Slot) -> T): T {
        val key = identity.sessionId to identity.slotId
        val slot = synchronized(slots) {
            slots.getOrPut(key, ::Slot).also { it.reservations++ }
        }
        try {
            return synchronized(slot) { block(slot) }
        } finally {
            synchronized(slots) {
                slot.reservations--
                if (slot.reservations == 0 && slot.owner == null) slots.remove(key)
            }
        }
    }
}

/** Exact run authority inherited by model/tool children of one activation. */
class AgentRunExecutionContext internal constructor(
    val identity: AgentRunIdentity,
    val parentRunId: String?,
    private val owners: AgentRunOwnership,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentRunExecutionContext>

    fun <T> commitCurrent(sessionId: String = identity.sessionId, action: () -> T): T {
        requireCurrent(sessionId)
        var result: T? = null
        if (!owners.commit(identity) { result = action() }) {
            throw CancellationException("任务执行所有权已失效：${identity.runId}")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun requireCurrent(sessionId: String = identity.sessionId) {
        if (identity.sessionId != sessionId || !owners.isCurrent(identity)) {
            throw CancellationException("任务执行所有权已失效：${identity.runId}")
        }
    }
}
