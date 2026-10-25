package com.labteto.dshmobile.local.agent

/**
 * Shared in-memory fanout cap for one foreground recursive run.
 * Does not replace RunOwnership, the real resource scheduler, or model admission.
 */
internal class LocalAgentSubtreeBudget(
    private val maxDescendants: Int = 4,
    private val maxDescendantModelSteps: Int = 48,
) {
    init {
        require(maxDescendants in 1..24)
        require(maxDescendantModelSteps in 1..512)
    }
    private var admitted = 0
    private var requests = 0
    private val admittedDelegations = hashSetOf<String>()
    @Synchronized fun claim() {
        require(admitted < maxDescendants) { "AGENT_SUBTREE_FANOUT_EXHAUSTED" }
        admitted++
    }
    /**
     * Unique identity combines the live immediate parent Run and its model tool call.
     * Duplicate provider/tool delivery must never create another descendant.
     */
    @Synchronized fun claim(delegationIdentity: String) {
        require(delegationIdentity.isNotBlank()) { "AGENT_SUBTREE_DELEGATION_ID_REQUIRED" }
        require(delegationIdentity !in admittedDelegations) { "AGENT_SUBTREE_DUPLICATE_DELEGATION" }
        require(admitted < maxDescendants) { "AGENT_SUBTREE_FANOUT_EXHAUSTED" }
        admittedDelegations.add(delegationIdentity)
        admitted++
    }

    @Synchronized fun used(): Int = admitted
    @Synchronized fun claimModelStep() {
        require(requests < maxDescendantModelSteps) { "AGENT_SUBTREE_MODEL_BUDGET_EXHAUSTED" }
        requests++
    }
    @Synchronized fun usedModelSteps(): Int = requests
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
