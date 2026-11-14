package com.labteto.dshmobile.local.work

/** E05: a replan forks one immutable TaskGraph definition into a new execution.
 * Read-only graphs are safe to retire before replacing dependencies. Writable
 * graphs carry physical side-effects and must be verified by Lead before mutation.
 */
internal data class LocalTaskGraphReplanDecision(
    val invalidatedNodeIds: Set<String>,
    val reusableNodeIds: Set<String>,
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
    // A whole-graph version change must not discard an already verified read-only
    // result if that node's own task, inputs, dependencies and execution contract
    // remain unchanged. Reuse still requires the old result to pass recovery checks.
    val stableRun = listOf("model", "route", "max_steps", "allowed_tools",
        "output_schema", "backend_binding", "workspace_repository", "pipeline")
        .all { previous.value[it] == proposed.value[it] }
    val filesDeclared = previous.value["input_files"] != null &&
        previous.value["required_files"] != null &&
        proposed.value["input_files"] != null && proposed.value["required_files"] != null &&
        previous.value["evidence"] != null && proposed.value["evidence"] != null
    val proposedById = proposed.nodes.withIndex().associate { it.value.id to it }
    val candidates = if (stableRun && filesDeclared) previous.nodes.withIndex()
        .filter { (index, oldNode) ->
            val target = proposedById[oldNode.id] ?: return@filter false
            val newNode = target.value
            oldNode.task == newNode.task &&
                oldNode.dependsOn == newNode.dependsOn &&
                previous.inputPaths(index) == proposed.inputPaths(target.index) &&
                previous.outputPaths(index) == proposed.outputPaths(target.index) &&
                previous.prepared.evidence.getOrNull(index) == proposed.prepared.evidence.getOrNull(target.index)
        }.mapTo(linkedSetOf()) { it.value.id }
    else linkedSetOf()
    // A result may be borrowed only when every dependency is also borrowed.
    // Otherwise a changed upstream node could make old downstream work stale.
    var changed: Boolean
    do {
        changed = candidates.removeAll { id ->
            proposedById.getValue(id).value.dependsOn.any { it !in candidates }
        }
    } while (changed)
    return LocalTaskGraphReplanDecision(
        invalidatedNodeIds = previous.nodes.mapTo(linkedSetOf()) { it.id } - candidates,
        reusableNodeIds = candidates,
        oldPlanVersion = previous.version,
        newPlanVersion = proposed.version,
    )
}

/**
 * A durable new start is the recovery cut-over point. Crashing before the older
 * taskgraph/end must NOT resume both DAGs or replay old model/tool invocations.
 * The old plan is allowed to be suppressed only for verified read-only replans.
 */
internal fun durableTaskGraphReplacement(
    log: com.labteto.dshmobile.local.session.LocalSessionEventLog,
    sessionId: String,
    oldGraphId: String,
    oldExecutionId: String,
    oldPlan: LocalWorkTaskGraphPlan,
): String? {
    if (oldPlan.writable) return null
    val expectedGoal = oldPlan.value["goal_fingerprint"] ?: return null
    val candidate = log.latestMatching(setOf("taskgraph/start")) { data ->
        data["replaces_graph_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == oldGraphId &&
            data["session_id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == sessionId &&
            (data["graph_execution_id"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.takeIf(String::isNotBlank)?.let { it != oldExecutionId } == true &&
            (data["graph_id"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.isNotBlank() == true &&
            data["goal_fingerprint"] == expectedGoal &&
            (data["plan"] as? kotlinx.serialization.json.JsonObject)?.let { plan ->
                plan["allow_mutation"]?.toString() == "false" &&
                    plan["goal_fingerprint"] == expectedGoal &&
                    LocalWorkTaskGraphPlan(plan).version != oldPlan.version
            } == true
    } ?: return null
    return (candidate.data["graph_id"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.takeIf(String::isNotBlank)
}
