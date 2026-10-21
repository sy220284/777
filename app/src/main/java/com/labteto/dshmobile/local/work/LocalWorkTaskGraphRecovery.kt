package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalSessionEventLog
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
    private val accepted = linkedMapOf<String, MutableMap<String, Long>>()
    private val jobResultsBySequence = linkedMapOf<Long, String?>()
    private val jobResultsById = linkedMapOf<String, Long>()
    private val admissions = linkedMapOf<Pair<String, String>, MutableList<Long>>()
    private val runsByAgent = linkedMapOf<String, MutableSet<String>>()
    private val startsByRun = linkedMapOf<String, MutableList<Long>>()
    private val resultsByJob = linkedMapOf<String, MutableList<Long>>()
    private val historyCheckpoints = linkedMapOf<String, Long>()
    private val callsByAgent = linkedMapOf<String, MutableList<Long>>()
    private val toolResults = linkedMapOf<Triple<String, String?, String?>, Long>()
    private class JobTools(val runs: Set<String>) {
        val ledger = ToolAttemptLedger()
        var throughSequence = -1L
    }
    private val toolsByJob = linkedMapOf<String, JobTools>()

    // Retain only sequence/identity indices, never complete output, arguments or history.
    // Closed graph/Job evidence remains addressable without truncating durable facts.
    private fun eventAt(log: LocalSessionEventLog, sequence: Long): LocalSessionEventLog.Event? =
        log.pageAfter(sequence - 1L, 1).firstOrNull()?.takeIf { it.sequence == sequence }

    private fun List<Long>.after(sequence: Long): List<Long> {
        val found = binarySearch(sequence)
        val from = if (found >= 0) found + 1 else -found - 1
        return subList(from, size)
    }

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
        callsByAgent.clear()
        toolResults.clear()
        toolsByJob.clear()
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
                    accepted.getOrPut(execution) { linkedMapOf() }[node] = event.sequence
                }
                "taskgraph/job-result" -> {
                    jobResultsBySequence[event.sequence] = execution
                    (data["job_id"] as? JsonPrimitive)?.contentOrNull?.let { jobResultsById[it] = event.sequence }
                }
                "taskgraph/job-admitted" -> if (!execution.isNullOrBlank() && !node.isNullOrBlank()) {
                    admissions.getOrPut(execution to node) { mutableListOf() }.add(event.sequence)
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
                    if (!runId.isNullOrBlank()) startsByRun.getOrPut(runId) { mutableListOf() }.add(event.sequence)
                }
                "subagent/tool-call" -> {
                    val agentId = (data["agent_id"] as? JsonPrimitive)?.contentOrNull
                    if (!agentId.isNullOrBlank()) callsByAgent.getOrPut(agentId) { mutableListOf() }.add(event.sequence)
                }
                "subagent/tool-result" -> {
                    val jobId = (data["background_job_id"] as? JsonPrimitive)?.contentOrNull
                    if (!jobId.isNullOrBlank()) {
                        resultsByJob.getOrPut(jobId) { mutableListOf() }.add(event.sequence)
                        val executionId = (data["execution_id"] as? JsonPrimitive)?.contentOrNull
                        val callId = (data["id"] as? JsonPrimitive)?.contentOrNull
                        toolResults[Triple(jobId, executionId, callId)] = event.sequence
                    }
                }
                LOCAL_SUBAGENT_HISTORY_CHECKPOINT_EVENT -> {
                    val jobId = (data["background_job_id"] as? JsonPrimitive)?.contentOrNull
                    val agentId = (data["agent_id"] as? JsonPrimitive)?.contentOrNull
                    // Match the authoritative reverse predicate: an unrelated checkpoint must
                    // not hide an earlier valid checkpoint for this persistent Job.
                    if (!jobId.isNullOrBlank() && agentId == localPersistentSubagentId(jobId)) {
                        historyCheckpoints[jobId] = event.sequence
                    }
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
        val indices = accepted[execution].orEmpty()
        val events = log.latestMatchingByKey(setOf("taskgraph/node-accepted"), indices.values.toSet()) { it.sequence }
        val facts = indices.mapNotNull { (node, sequence) ->
            events[sequence]?.let { node to it.data }
        }.toMap()
        val resultSequences = facts.values.mapNotNull {
            (it["result_sequence"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        }.filter { jobResultsBySequence[it] == execution }.toSet()
        val results = log.latestMatchingByKey(setOf("taskgraph/job-result"), resultSequences) { it.sequence }
            .mapValues { it.value.data }
        return facts to results
    }

    @Synchronized
    fun acceptedNode(log: LocalSessionEventLog, execution: String, nodeId: String): LocalSessionEventLog.Event? {
        refresh(log)
        return accepted[execution]?.get(nodeId)?.let { eventAt(log, it) }
    }

    @Synchronized
    fun nodeAdmissions(log: LocalSessionEventLog, execution: String, nodeId: String): List<JsonObject> {
        refresh(log)
        val sequences = admissions[execution to nodeId].orEmpty()
        val events = log.latestMatchingByKey(setOf("taskgraph/job-admitted"), sequences.toSet()) { it.sequence }
        return sequences.mapNotNull { events[it]?.data }
    }

    @Synchronized
    fun jobResult(log: LocalSessionEventLog, jobId: String): LocalSessionEventLog.Event? {
        refresh(log)
        return jobResultsById[jobId]?.let { eventAt(log, it) }
    }

    /**
     * Replay only events belonging to the named persistent Job. The original reducer still owns
     * admission, call-id reuse, execution-id identity and unknown-effect semantics.
     */
    @Synchronized
    fun toolAttempts(log: LocalSessionEventLog, jobId: String): List<ToolAttemptLedger.Attempt> {
        refresh(log)
        val agent = localPersistentSubagentId(jobId)
        val runs = runsByAgent[agent].orEmpty()
        val state = toolsByJob[jobId]?.takeIf { it.runs == runs }
            ?: JobTools(runs.toSet()).also { toolsByJob[jobId] = it }
        // Only newly appended facts are reduced. If a late checkpoint associates an older
        // Run, rebuild from the same durable indices so cold and live recovery agree.
        val sequences = runs.flatMap { startsByRun[it].orEmpty().after(state.throughSequence) } +
            resultsByJob[jobId].orEmpty().after(state.throughSequence) +
            callsByAgent[agent].orEmpty().after(state.throughSequence)
        val remaining = sequences.toMutableSet()
        // Read the new tail once, rather than reopening/redecoding the segment per fact.
        try {
            if (remaining.isNotEmpty()) log.forEachAfter(state.throughSequence) { event ->
                if (!remaining.remove(event.sequence)) return@forEachAfter
                val type = when (event.type) {
                    "subagent/tool-call" -> "tool/call"
                    "subagent/tool-result" -> "tool/result"
                    else -> event.type
                }
                state.ledger.observe(type, event.data)
            }
            // An indexed admission cannot disappear during reread and become a safe result.
            check(remaining.isEmpty()) { "任务工具执行记录读取不完整，无法确认执行结果。" }
        } catch (error: Exception) {
            // A partial fold must be rebuilt before retry, including declarations.
            toolsByJob.remove(jobId)
            throw error
        }
        state.throughSequence = throughSequence
        return state.ledger.snapshot()
    }

    @Synchronized
    fun historyCheckpoint(log: LocalSessionEventLog, jobId: String): LocalSessionEventLog.Event? {
        refresh(log)
        return historyCheckpoints[jobId]?.let { eventAt(log, it) }?.takeIf { checkpoint ->
            (checkpoint.data["agent_id"] as? JsonPrimitive)?.contentOrNull == localPersistentSubagentId(jobId)
        }
    }

    @Synchronized
    fun toolResult(log: LocalSessionEventLog, jobId: String, executionId: String, callId: String): LocalSessionEventLog.Event? {
        refresh(log)
        return toolResults[Triple(jobId, executionId, callId)]?.let { eventAt(log, it) }
    }
}

/** Shares exactly one incremental projection per live EventLog, without owning the log itself. */
internal object LocalWorkRecoveryProjections {
    private val projections = WeakHashMap<LocalSessionEventLog, LocalWorkOpenGraphProjection>()

    fun forLog(log: LocalSessionEventLog): LocalWorkOpenGraphProjection =
        synchronized(projections) { projections.getOrPut(log) { LocalWorkOpenGraphProjection() } }
}
