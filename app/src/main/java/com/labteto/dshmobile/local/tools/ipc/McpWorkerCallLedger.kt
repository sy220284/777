package com.labteto.dshmobile.local.tools.ipc

/**
 * One worker transport incarnation owns its in-flight remote side effects.
 *
 * Termination closes admission AND invalidates every pending ticket. A late
 * response from an RPC already executing after CLOSE/worker revocation may
 * never be published as success. The caller must explicitly reconnect instead
 * of replaying an unknown side effect.
 */
internal class McpWorkerCallLedger(
    private val maxInFlight: Int = McpWorkerWire.MAX_PENDING_CALLS,
) {
    init { require(maxInFlight in 1..64) }

    private var admitted = true
    private var nextTicket = 0L
    private val pending = mutableSetOf<Long>()

    @Synchronized
    fun begin(): Long? {
        if (!admitted || pending.size >= maxInFlight) return null
        val ticket = ++nextTicket
        pending.add(ticket)
        return ticket
    }

    /** True only if the same incarnation is still admitted at result commit. */
    @Synchronized
    fun complete(ticket: Long): Boolean {
        val wasPending = pending.remove(ticket)
        return admitted && wasPending
    }

    @Synchronized
    fun abandon(ticket: Long) {
        pending.remove(ticket)
    }

    /** Idempotent: never reopens the old worker or its old tool approvals. */
    @Synchronized
    fun revoke(): Int {
        admitted = false
        val outstanding = pending.size
        pending.clear()
        return outstanding
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}
