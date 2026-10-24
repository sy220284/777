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

/** Child grants are always reduced and never retain a delegation tool at the leaf. */
internal fun compileNestedReadonlyCapabilities(parent: LocalSubagentCapabilities): LocalSubagentCapabilities {
    require(!parent.allowMutation && !parent.continuable && !parent.teamManaged &&
        !parent.virtualScreen && parent.depth < parent.maxDepth) {
        "NESTED_DELEGATION_SCOPE_INVALID"
    }
    val nextDepth = parent.depth + 1
    val childTools = parent.toolAllowlist?.let { allowed ->
        if (nextDepth >= parent.maxDepth) allowed - "subagent" else allowed
    }
    return parent.copy(
        allowMutation = false,
        continuable = false,
        virtualScreen = false,
        historyMode = LocalSubagentHistoryMode.ISOLATED,
        depth = nextDepth,
        toolAllowlist = childTools,
        outputSchema = null,
        teamManaged = false,
        initialOptionalTools = emptySet(),
    )
}
