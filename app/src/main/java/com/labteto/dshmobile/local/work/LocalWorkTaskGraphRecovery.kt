package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A typed read-only projector for Work's durable taskgraph/start and taskgraph/end facts.
 * It never schedules work or claims success. A started graph without a matching
 * terminal fact is unresolved after a crash; the admitted Work root slice decides recovery.
 */
internal class LocalWorkTaskGraphRecoveryLedger {
    internal data class OpenGraph(
        val graphId: String,
        val goalId: String,
        val goalFingerprint: String?,
        val graphExecutionId: String? = null,
        val plan: JsonObject? = null,
        val lastNodeStages: Map<String, String> = emptyMap(),
    )

    // Graph IDs should be unique, but old logs or multiple activations can reuse them.
    // Never overwrite an earlier unclosed graph with a later start of the same ID.
    private val open = linkedMapOf<Pair<String, String>, MutableList<OpenGraph>>()

    fun observe(type: String, data: JsonObject) {
        val graphId = (data["graph_id"] as? JsonPrimitive)
            ?.contentOrNull?.takeIf(String::isNotBlank) ?: return
        val goalId = (data["goal_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val key = graphId to goalId
        val executionId = (data["graph_execution_id"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
        when (type) {
            "taskgraph/start" -> {
                open.getOrPut(key) { mutableListOf() } += OpenGraph(
                    graphId, goalId,
                    (data["goal_fingerprint"] as? JsonPrimitive)?.contentOrNull,
                    graphExecutionId = executionId,
                    plan = data["plan"] as? JsonObject,
                )
            }
            "taskgraph/progress" -> {
                val candidates = open[key] ?: return
                // Modern progress is bound to its own durable activation.
                // Legacy progress may update only a unique legacy activation.
                val index = candidates.indexOfFirst { it.graphExecutionId == executionId }
                if (index < 0 || candidates.count { it.graphExecutionId == executionId } != 1) return
                val running = candidates[index]
                val nodeId = (data["node_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val stage = (data["stage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (nodeId.isNotBlank() && stage.isNotBlank()) {
                    candidates[index] = running.copy(
                        lastNodeStages = running.lastNodeStages + (nodeId to stage),
                    )
                }
            }
            "taskgraph/end" -> {
                // End events in current logs carry the original acceptance fingerprint.
                // A delayed terminal from an earlier Goal revision must not close a
                // newer graph with a reused provider-supplied ID.
                val fingerprint = (data["goal_fingerprint"] as? JsonPrimitive)?.contentOrNull
                val candidates = open[key] ?: return
                val matching = candidates.indices.filter { index ->
                    candidates[index].graphExecutionId == executionId &&
                        (fingerprint == null || candidates[index].goalFingerprint == fingerprint)
                }
                if (matching.isEmpty()) return
                // A missing execution identity cannot settle a modern run.
                // Old logs may settle only one unambiguous legacy activation.
                if (matching.size != 1) return
                candidates.removeAt(matching.single())
                if (candidates.isEmpty()) open.remove(key)
            }
        }
    }

    fun unresolved(): List<OpenGraph> = open.values.flatMap { it }

    fun unresolvedForGoal(goalId: String): List<OpenGraph> =
        open.filterKeys { it.second == goalId }.values.flatMap { it }
}

/** Fail closed until the run/recovery owner can reconcile a graph left without its end fact. */
internal fun hasUnsettledWorkGoalTaskGraphs(
    eventLog: LocalSessionEventLog,
    goal: LocalGoal,
): Boolean {
    if (goal.id.isBlank()) return false
    val ledger = LocalWorkTaskGraphRecoveryLedger()
    val from = goal.startedAtSequence.takeIf { it >= 0L } ?: -1L
    eventLog.forEachAfter(from) { event ->
        ledger.observe(event.type, event.data)
    }
    return ledger.unresolvedForGoal(goal.id).isNotEmpty()
}
