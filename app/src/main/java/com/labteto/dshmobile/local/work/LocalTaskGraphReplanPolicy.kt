package com.labteto.dshmobile.local.work

/** E05: a replan forks one immutable TaskGraph definition into a new execution.
 * Read-only graphs are safe to retire before replacing dependencies. Writable
 * graphs carry physical side-effects and must be verified by Lead before mutation.
 */
internal data class LocalTaskGraphReplanDecision(
    val invalidatedNodeIds: Set<String>,
    val oldPlanVersion: String,
    val newPlanVersion: String,
)

internal fun admitLocalTaskGraphReplan(
    previous: LocalWorkTaskGraphPlan,
    proposed: LocalWorkTaskGraphPlan,
): LocalTaskGraphReplanDecision {
    require(!previous.writable && !proposed.writable) {
        "TASKGRAPH_REPLAN_WRITABLE_REQUIRES_INDEPENDENT_VERIFICATION"
    }
    require(previous.value["goal_fingerprint"] == proposed.value["goal_fingerprint"]) {
        "TASKGRAPH_REPLAN_GOAL_CHANGED"
    }
    require(previous.version != proposed.version) { "TASKGRAPH_REPLAN_NO_CHANGES" }
    require(proposed.nodes.isNotEmpty()) { "TASKGRAPH_REPLAN_EMPTY" }
    // A revised dependency changes the graph's overall hash and every node's
    // immutable version. Never impersonate already accepted old node results.
    return LocalTaskGraphReplanDecision(
        invalidatedNodeIds = previous.nodes.mapTo(linkedSetOf()) { it.id },
        oldPlanVersion = previous.version,
        newPlanVersion = proposed.version,
    )
}
