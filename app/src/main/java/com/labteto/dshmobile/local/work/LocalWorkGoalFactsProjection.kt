package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One event cut shared by completion and continuation; live file evidence stays outside it. */
internal data class LocalWorkGoalFacts(
    val goalId: String,
    val throughSequence: Long,
    val attempts: List<ToolAttemptLedger.Attempt>,
    val hasUnsettledGraphs: Boolean,
    val appliedEvents: Int,
) {
    fun hasUnresolvedTools(executingToolId: String? = null): Boolean = attempts.any {
        it.goalId == goalId && it.unresolvedExternalEffect &&
            (executingToolId == null || it.executionId != executingToolId)
    }
}

@Serializable
private data class GoalFactsCheckpoint(
    val goalId: String,
    val start: Long,
    val tools: String,
    val graphs: List<LocalWorkTaskGraphRecoveryLedger.OpenGraph>,
)

/** Work-owned disposable projection, using the existing Session checkpoint/lifecycle boundary. */
// FEATURE_TREE: {"id":"work.goal.incremental-facts","path":"第一层｜产品功能与界面 / 工作与智能体功能 / 工作空间和成果 / 目标事实增量判断与恢复（未合并）","entry":"LocalWorkGoalFactsProjection","owner":"WORK","test":"app/src/test/java/com/labteto/dshmobile/local/work/LocalWorkGoalFactsProjectionTest.kt#repeatedAssessmentAppliesOnlyNewEventsAndMatchesFullRebuild"}
internal class LocalWorkGoalFactsProjection {
    private var generation = -1L
    private var goalId: String? = null
    private var start = -1L
    private var through = -1L
    private var tools = ToolAttemptLedger()
    private var graphs = LocalWorkTaskGraphRecoveryLedger()

    fun assess(log: LocalSessionEventLog, goal: LocalGoal): LocalWorkGoalFacts = log.withWriterLease { synchronized(this) {
        check(!log.isClosed) { "会话已关闭，无法判断任务执行事实。" }
        val from = goal.startedAtSequence.takeIf { it >= 0L } ?: -1L
        if (generation != log.resetGeneration || goalId != goal.id || start != from ||
            through > log.latestSequence()) {
            generation = log.resetGeneration
            goalId = goal.id
            start = from
            through = from
            tools = ToolAttemptLedger()
            graphs = LocalWorkTaskGraphRecoveryLedger()
            val checkpoint = log.readProjectionCheckpoint(NAME, VERSION)
            if (checkpoint != null && checkpoint.throughSequence >= from) {
                try {
                    val saved = Json.decodeFromString(GoalFactsCheckpoint.serializer(), checkpoint.payload)
                    if (saved.goalId == goal.id && saved.start == from) {
                        tools = ToolAttemptLedger.fromCheckpoint(saved.tools)
                        graphs = LocalWorkTaskGraphRecoveryLedger(saved.graphs)
                        through = checkpoint.throughSequence
                    }
                } catch (_: SerializationException) {
                    // Optional acceleration is damaged; retain the original-log rebuild.
                } catch (_: IllegalArgumentException) {
                    // An inconsistent reducer checkpoint cannot certify execution results.
                }
            }
        }
        val latest = log.latestSequence()
        var applied = 0
        val diagnostics = log.diagnostics()
        try {
            log.forEachAfter(through) { event ->
                tools.observe(event.type, event.data)
                // Goal facts need identity/stage, never the full persisted graph plan.
                val data = if (event.type == "taskgraph/start")
                    kotlinx.serialization.json.JsonObject(event.data - "plan") else event.data
                graphs.observe(event.type, data)
                through = event.sequence
                applied++
            }
            check(log.diagnostics().segmentReadFailures == diagnostics.segmentReadFailures &&
                log.diagnostics().malformedRows == diagnostics.malformedRows) { "任务执行事实读取不完整。" }
            check(through >= latest || latest <= from) { "任务执行事实读取不完整。" }
            check(!log.isClosed && generation == log.resetGeneration) { "任务投影期间会话已重置。" }
        } catch (failure: Exception) {
            generation = -1L // Never retain a partially applied reducer across a failed read.
            throw failure
        }
        if (applied > 0) log.writeProjectionCheckpointIfFits(NAME, VERSION, through,
            Json.encodeToString(GoalFactsCheckpoint.serializer(), GoalFactsCheckpoint(
                goal.id, from, tools.checkpoint(), graphs.unresolved())), generation)
        val result = LocalWorkGoalFacts(goal.id, through, tools.snapshot(),
            graphs.unresolvedForGoal(goal.id).isNotEmpty(), applied)
        if (result.attempts.size + graphs.unresolved().size > 4_096) {
            generation = -1L
            tools = ToolAttemptLedger()
            graphs = LocalWorkTaskGraphRecoveryLedger()
        }
        result
    } }

    private companion object {
        const val NAME = "work.goal-facts"
        const val VERSION = 1
    }
}
