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

    private val open = linkedMapOf<String, OpenGraph>()

    fun observe(type: String, data: JsonObject) {
        val graphId = (data["graph_id"] as? JsonPrimitive)
            ?.contentOrNull?.takeIf(String::isNotBlank) ?: return
        val goalId = (data["goal_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        when (type) {
            "taskgraph/start" -> {
                // Graph-less legacy events and user-independent workflow sessions
                // cannot be confused with an existing Work Goal.
                if (goalId.isNotBlank()) {
                    open[graphId] = OpenGraph(
                        graphId, goalId,
                        (data["goal_fingerprint"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
            }
            "taskgraph/progress" -> {
                val running = open[graphId] ?: return
                if (running.goalId != goalId) return
                val nodeId = (data["node_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val stage = (data["stage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (nodeId.isNotBlank() && stage.isNotBlank()) {
                    open[graphId] = running.copy(
                        lastNodeStages = running.lastNodeStages + (nodeId to stage),
                    )
                }
            }
            "taskgraph/end" -> {
                // Only an end for the same identity may settle a started graph.
                // This protects against corrupt or misattributed historical events.
                if (open[graphId]?.goalId == goalId) open.remove(graphId)
            }
        }
    }

    fun unresolvedForGoal(goalId: String): List<OpenGraph> =
        open.values.filter { it.goalId == goalId }
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
