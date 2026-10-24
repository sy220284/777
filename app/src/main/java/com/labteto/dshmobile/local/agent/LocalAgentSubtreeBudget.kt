package com.labteto.dshmobile.local.agent

/**
 * Shared in-memory fanout cap for one foreground recursive run.
 * Does not replace RunOwnership, the real resource scheduler, or model admission.
 */
internal class LocalAgentSubtreeBudget(private val maxDescendants: Int = 4) {
    init { require(maxDescendants in 1..24) }
    private var admitted = 0
    @Synchronized fun claim() {
        require(admitted < maxDescendants) { "AGENT_SUBTREE_FANOUT_EXHAUSTED" }
        admitted++
    }
    @Synchronized fun used(): Int = admitted
}
