package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A typed read-only projector for Work's durable taskgraph/start and taskgraph/end facts.
 * It never schedules work or claims success. A started graph without a matching
 * terminal fact is unresolved after a crash, and must not be replayed automatically.
 */
internal class LocalWorkTaskGraphRecoveryLedger {
    internal data class OpenGraph(
        val graphId: String,
        val goalId: String,
        val goalFingerprint: String?,
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
        when (type) {
            "taskgraph/start" -> {
                // Graph-less legacy events and user-independent workflow sessions
                // cannot be confused with an existing Work Goal.
                if (goalId.isNotBlank()) {
                    open.getOrPut(key) { mutableListOf() } += OpenGraph(
                        graphId, goalId,
                        (data["goal_fingerprint"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
            }
            "taskgraph/progress" -> {
                val running = open[key]?.singleOrNull() ?: return
                // Progress has no fingerprint. Under duplicate identity it cannot
                // safely be associated with an individual activation.
                val nodeId = (data["node_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val stage = (data["stage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (nodeId.isNotBlank() && stage.isNotBlank()) {
                    open.getValue(key)[0] = running.copy(
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
                    fingerprint == null || candidates[index].goalFingerprint == fingerprint
                }
                if (matching.isEmpty()) return
                // Legacy terminals lack a fingerprint. If several distinct contracts
                // share the ID, there is no trustworthy way to identify the owner.
                if (fingerprint == null && candidates.map { it.goalFingerprint }.distinct().size > 1) return
                // Settle exactly one activation, preserving an earlier unclosed run.
                candidates.removeAt(matching.last())
                if (candidates.isEmpty()) open.remove(key)
            }
        }
    }

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
