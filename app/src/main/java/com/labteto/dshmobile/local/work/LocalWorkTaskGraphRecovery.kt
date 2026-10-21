package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.harness.session.SessionEvent
import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import java.util.WeakHashMap
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

/** A disposable, read-only incremental fold of the authoritative Session log. */
/**
 * One disposable, read-only fold of the authoritative Session EventLog.
 *
 * Graph discovery, admitted node facts, durable Job results and tool attempts must share this
 * cursor. No duplicate persistence, no inferred success, and no replay of unknown mutations.
 * Each query still validates current file/dependency versions at the execution owner.
 */
internal class LocalWorkOpenGraphProjection {
    private var generation = -1L
    private var throughSequence = -1L
    private var ledger = LocalWorkTaskGraphRecoveryLedger()
    private val accepted = linkedMapOf<Pair<String, String>, SessionEvent>()
    private val jobResultsBySequence = linkedMapOf<Long, SessionEvent>()
    private val jobResultsById = linkedMapOf<String, SessionEvent>()
    private val admissions = linkedMapOf<Pair<String, String>, MutableList<JsonObject>>()
    private val runsByAgent = linkedMapOf<String, MutableSet<String>>()
    private val startsByRun = linkedMapOf<String, MutableList<SessionEvent>>()
    private val resultsByJob = linkedMapOf<String, MutableList<SessionEvent>>()
    private val historyCheckpoints = linkedMapOf<String, SessionEvent>()

    private fun reset() {
        throughSequence = -1L
        ledger = LocalWorkTaskGraphRecoveryLedger()
        accepted.clear()
        jobResultsBySequence.clear()
        jobResultsById.clear()
        admissions.clear()
        runsByAgent.clear()
        startsByRun.clear()
        resultsByJob.clear()
        historyCheckpoints.clear()
    }

    private fun refresh(log: LocalSessionEventLog) {
        val latest = log.latestSequence()
        if (generation != log.resetGeneration || latest < throughSequence) {
            generation = log.resetGeneration
            reset()
        }
        if (latest == throughSequence) return
        log.forEachAfter(throughSequence) { event ->
            ledger.observe(event.type, event.data)
            val data = event.data
            val execution = (data["graph_execution_id"] as? JsonPrimitive)?.contentOrNull
            val node = (data["node_id"] as? JsonPrimitive)?.contentOrNull
            when (event.type) {
                "taskgraph/node-accepted" -> if (!execution.isNullOrBlank() && !node.isNullOrBlank()) {
                    accepted[execution to node] = event
                }
                "taskgraph/job-result" -> {
                    jobResultsBySequence[event.sequence] = event
                    (data["job_id"] as? JsonPrimitive)?.contentOrNull?.let { jobResultsById[it] = event }
                }
                "taskgraph/job-admitted" -> if (!execution.isNullOrBlank() && !node.isNullOrBlank()) {
                    admissions.getOrPut(execution to node) { mutableListOf() }.add(data)
                }
                "agent/subagent-run-checkpoint" -> {
                    val agentId = (data["agent_id"] as? JsonPrimitive)?.contentOrNull
                    val runId = (data["run_id"] as? JsonPrimitive)?.contentOrNull
                    if (!agentId.isNullOrBlank() && !runId.isNullOrBlank()) {
                        runsByAgent.getOrPut(agentId) { linkedSetOf() }.add(runId)
                    }
                }
                "tool/execution-started" -> {
                    val attributes = data["attributes"] as? JsonObject
                    val runId = (attributes?.get("run_id") as? JsonPrimitive)?.contentOrNull
                    if (!runId.isNullOrBlank()) startsByRun.getOrPut(runId) { mutableListOf() }.add(event)
                }
                "subagent/tool-result" -> {
                    val jobId = (data["background_job_id"] as? JsonPrimitive)?.contentOrNull
                    if (!jobId.isNullOrBlank()) resultsByJob.getOrPut(jobId) { mutableListOf() }.add(event)
                }
                LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT -> {
                    val jobId = (data["background_job_id"] as? JsonPrimitive)?.contentOrNull
                    if (!jobId.isNullOrBlank()) historyCheckpoints[jobId] = event
                }
            }
            throughSequence = event.sequence
        }
    }

    @Synchronized
    fun unresolved(log: LocalSessionEventLog): List<LocalWorkTaskGraphRecoveryLedger.OpenGraph> {
        refresh(log)
        return ledger.unresolved()
    }

    @Synchronized
    fun acceptedFor(log: LocalSessionEventLog, execution: String): Pair<Map<String, JsonObject>, Map<Long, JsonObject>> {
        refresh(log)
        val facts = accepted.asSequence().filter { it.key.first == execution }
            .associate { it.key.second to it.value.data }
        val results = facts.values.mapNotNull { (it["result_sequence"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
            .distinct().mapNotNull { sequence ->
                jobResultsBySequence[sequence]?.takeIf { event ->
                    (event.data["graph_execution_id"] as? JsonPrimitive)?.contentOrNull == execution
                }?.let { sequence to it.data }
            }.toMap()
        return facts to results
    }

    @Synchronized
    fun acceptedNode(log: LocalSessionEventLog, execution: String, nodeId: String): SessionEvent? {
        refresh(log)
        return accepted[execution to nodeId]
    }

    @Synchronized
    fun nodeAdmissions(log: LocalSessionEventLog, execution: String, nodeId: String): List<JsonObject> {
        refresh(log)
        return admissions[execution to nodeId]?.toList().orEmpty()
    }

    @Synchronized
    fun jobResult(log: LocalSessionEventLog, jobId: String): SessionEvent? {
        refresh(log)
        return jobResultsById[jobId]
    }

    /**
     * Replay only events belonging to the named persistent Job. The original reducer still owns
     * admission, call-id reuse, execution-id identity and unknown-effect semantics.
     */
    @Synchronized
    fun toolAttempts(log: LocalSessionEventLog, jobId: String): List<ToolAttemptLedger.Attempt> {
        refresh(log)
        val runs = runsByAgent[localPersistentSubagentId(jobId)].orEmpty()
        val events = runs.flatMap { startsByRun[it].orEmpty() } + resultsByJob[jobId].orEmpty()
        val attempts = ToolAttemptLedger()
        events.sortedBy(SessionEvent::sequence).forEach { event ->
            if (event.type == "subagent/tool-result") attempts.observe("tool/result", event.data)
            else attempts.observe(event)
        }
        return attempts.snapshot()
    }

    @Synchronized
    fun historyCheckpoint(log: LocalSessionEventLog, jobId: String): SessionEvent? {
        refresh(log)
        return historyCheckpoints[jobId]?.takeIf { checkpoint ->
            (checkpoint.data["agent_id"] as? JsonPrimitive)?.contentOrNull == localPersistentSubagentId(jobId)
        }
    }

    @Synchronized
    fun toolResult(log: LocalSessionEventLog, jobId: String, executionId: String, callId: String): SessionEvent? {
        refresh(log)
        return resultsByJob[jobId]?.asReversed()?.firstOrNull { event ->
            (event.data["execution_id"] as? JsonPrimitive)?.contentOrNull == executionId &&
                (event.data["id"] as? JsonPrimitive)?.contentOrNull == callId
        }
    }
}

/** Shares exactly one incremental projection per live EventLog, without owning the log itself. */
internal object LocalWorkRecoveryProjections {
    private val projections = WeakHashMap<LocalSessionEventLog, LocalWorkOpenGraphProjection>()

    fun forLog(log: LocalSessionEventLog): LocalWorkOpenGraphProjection =
        synchronized(projections) { projections.getOrPut(log) { LocalWorkOpenGraphProjection() } }
}
