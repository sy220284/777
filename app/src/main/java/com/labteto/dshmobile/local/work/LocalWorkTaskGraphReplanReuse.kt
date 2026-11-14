package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Idempotent, source-verified reuse for a read-only TaskGraph replacement.
 *
 * The predecessor's immutable job result remains the source of truth. This
 * method only creates a new acceptance reference after its task, dependency
 * graph, permissions, model route, file versions and durable output have been
 * checked. Running it again after process death is safe.
 */
internal fun seedReusableReadOnlyGraphResults(
    log: LocalSessionEventLog,
    workspaceRoot: File,
    graphId: String,
    executionId: String,
    goalId: String,
    plan: LocalWorkTaskGraphPlan,
): Int {
    val previousGraphId = (plan.value["reuses_graph_id"] as? JsonPrimitive)
        ?.contentOrNull ?: return 0
    val previousExecution = (plan.value["reuses_graph_execution_id"] as? JsonPrimitive)
        ?.contentOrNull ?: error("TASKGRAPH_REUSE_EXECUTION_MISSING")
    require(!plan.writable && previousExecution != executionId) {
        "TASKGRAPH_REUSE_REQUIRES_DISTINCT_READONLY_GRAPH"
    }
    val source = log.latestMatching(setOf("taskgraph/start")) { data ->
        (data["graph_id"] as? JsonPrimitive)?.contentOrNull == previousGraphId &&
            (data["graph_execution_id"] as? JsonPrimitive)?.contentOrNull == previousExecution &&
            data["goal_fingerprint"] == plan.value["goal_fingerprint"]
    } ?: error("TASKGRAPH_REUSE_SOURCE_NOT_FOUND")
    val previous = LocalWorkTaskGraphPlan(
        source.data["plan"] as? JsonObject ?: error("TASKGRAPH_REUSE_SOURCE_PLAN_MISSING"),
    )
    val decision = admitLocalTaskGraphReplan(previous, plan)
    if (decision.reusableNodeIds.isEmpty()) return 0
    // Every accepted source result is rechecked against today's actual files.
    val oldResults = previous.recoveredResults(log, previousExecution, workspaceRoot)
    val candidates = decision.reusableNodeIds.intersect(oldResults.keys)
    val recovery = LocalWorkRecoveryProjections.forLog(log)
    val acceptedHere = mutableSetOf<String>()
    var count = 0
    // Topological fixed point also supports forward-declared dependency IDs.
    var remainingPasses = plan.nodes.size
    while (remainingPasses-- > 0) {
        val before = acceptedHere.size
        plan.nodes.forEachIndexed { index, node ->
            if (node.id !in candidates || node.id in acceptedHere ||
                node.dependsOn.any { it !in acceptedHere }) return@forEachIndexed
            val original = recovery.acceptedNode(log, previousExecution, node.id)
                ?.data ?: return@forEachIndexed
            if (original["node_version"] != JsonPrimitive(previous.nodeVersion(node)) ||
                original["goal_fingerprint"] != plan.value["goal_fingerprint"] ||
                original["files"] != plan.fileVersions(workspaceRoot, index)) return@forEachIndexed
            val sourceJobId = (original["job_id"] as? JsonPrimitive)?.contentOrNull
                ?: return@forEachIndexed
            val sourceSequence = (original["result_sequence"] as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: return@forEachIndexed
            val sourceResult = recovery.jobResult(log, sourceJobId)
                ?.takeIf { it.sequence == sourceSequence }?.data ?: return@forEachIndexed
            if (sourceResult["goal_fingerprint"] != plan.value["goal_fingerprint"] ||
                sourceResult["node_version"] == null) return@forEachIndexed
            if (recovery.acceptedNode(log, executionId, node.id) == null) {
                log.append("taskgraph/node-accepted", buildJsonObject {
                    put("graph_id", graphId)
                    put("graph_execution_id", executionId)
                    put("goal_id", goalId)
                    put("goal_fingerprint", plan.value.getValue("goal_fingerprint"))
                    put("node_id", node.id)
                    put("node_version", plan.nodeVersion(node))
                    put("job_id", original.getValue("job_id"))
                    put("result_sequence", original.getValue("result_sequence"))
                    put("output_version", original.getValue("output_version"))
                    put("files", original.getValue("files"))
                    put("dependencies", original.getValue("dependencies"))
                    put("dependency_versions", original.getValue("dependency_versions"))
                    put("acceptance", "source_verified_readonly_reuse")
                    put("source_job_node_version", sourceResult.getValue("node_version"))
                    put("reused_from_execution_id", previousExecution)
                    put("reused_from_graph_id", previousGraphId)
                })
                count++
            }
            acceptedHere += node.id
        }
        if (acceptedHere.size == before) break
    }
    return count
}
