package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A decision projection; Work Goal state and the durable Agent inbox retain their existing owners. */
internal enum class LocalWorkGoalDriveResult {
    IDLE, STALE, WAITING, VERIFICATION_REQUIRED, COMPLETED, CONTINUE_QUEUED, PARTIAL,
}

internal fun decideLocalWorkGoalDrive(
    conditions: LocalWorkGoalFollowUpConditions,
    hasMachineAcceptance: Boolean,
): LocalWorkGoalDriveResult {
    val goal = conditions.currentGoal ?: return LocalWorkGoalDriveResult.IDLE
    if (goal.status == "completed") return LocalWorkGoalDriveResult.IDLE
    if (goal != conditions.originalGoal || !conditions.steeringUnchanged || conditions.cancelled) {
        return LocalWorkGoalDriveResult.STALE
    }
    if (conditions.pendingInputs > 0 || !conditions.runtimeHealthy || conditions.planMode || goal.status != "active") {
        return LocalWorkGoalDriveResult.WAITING
    }
    return when (conditions.disposition) {
        LocalWorkGoalDisposition.READY_FOR_SETTLEMENT ->
            if (hasMachineAcceptance) LocalWorkGoalDriveResult.COMPLETED
            else LocalWorkGoalDriveResult.VERIFICATION_REQUIRED
        LocalWorkGoalDisposition.NEEDS_EXECUTION ->
            if (canAutomaticallyFollowUpWorkGoal(conditions)) LocalWorkGoalDriveResult.CONTINUE_QUEUED
            else LocalWorkGoalDriveResult.PARTIAL
        else -> LocalWorkGoalDriveResult.WAITING
    }
}

internal fun localWorkGoalBaselineForTurn(
    original: LocalGoal?,
    current: LocalGoal,
    runStartSequence: Long?,
): LocalGoal? = original ?: current.takeIf {
    it.revision == 1L && runStartSequence != null && it.startedAtSequence > runStartSequence
}


/**
 * A tool-authored Goal note update in the same Run is not an external goal replacement.
 * Every changed revision must be tied to a settled, correctly owned execution instance.
 */
internal fun localWorkGoalBaselineAfterVerifiedToolUpdates(
    original: LocalGoal,
    current: LocalGoal,
    sourceRunId: String,
    runStartSequence: Long?,
    eventLog: LocalSessionEventLog,
): LocalGoal {
    if (current == original) return original
    if (runStartSequence == null || original.id.isBlank() ||
        original.id != current.id || original.status != "active" || current.status != "active" ||
        original.description != current.description ||
        original.startedAtSequence != current.startedAtSequence ||
        original.artifactPaths != current.artifactPaths ||
        original.verificationCommands != current.verificationCommands ||
        current.revision <= original.revision
    ) return original

    val ledger = ToolAttemptLedger()
    val revisions = mutableListOf<kotlinx.serialization.json.JsonObject>()
    eventLog.forEachAfter(runStartSequence) { event ->
        ledger.observe(event.type, event.data)
        if (event.type == "goal/state") revisions += event.data
    }
    if (revisions.size.toLong() != current.revision - original.revision) return original
    val settledExecutions = ledger.snapshot().filter {
        it.started && it.settled && !it.outcomeUncertain &&
            it.runId == sourceRunId && it.executionId != null
    }.associateBy { it.executionId }
    return if (revisions.withIndex().all { (index, mutation) ->
        (mutation["id"] as? JsonPrimitive)?.contentOrNull == current.id &&
            (mutation["status"] as? JsonPrimitive)?.contentOrNull == "active" &&
            (mutation["revision"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ==
                original.revision + index + 1L &&
            (mutation["origin_execution_id"] as? JsonPrimitive)?.contentOrNull
                ?.let(settledExecutions::containsKey) == true
    }) current else original
}

/** Invoked by the Work run owner after checkpoint flush and before durable queue handoff. */
internal fun driveLocalWorkGoalAfterTurn(
    binding: LocalWorkRunBinding,
    originalGoal: LocalGoal?,
    sourceRunId: String,
    sourceMessageId: String?,
    originalSteeringRevision: Long,
    hadSuccessfulTool: Boolean,
    hadToolFailure: Boolean,
    workspaceRoot: File,
    completeGoal: (LocalGoal) -> Boolean,
): LocalWorkGoalDriveResult {
    val work = binding.workState.snapshot()
    val goal = work.goal ?: return LocalWorkGoalDriveResult.IDLE
    if (hasOutstandingWorkReplyForm(binding.eventLog)) return LocalWorkGoalDriveResult.WAITING
    val start = binding.eventLog.latestMatching(
        setOf(com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT),
    ) { data ->
        (data["run_id"] as? JsonPrimitive)?.contentOrNull == sourceRunId &&
            (data["phase"] as? JsonPrimitive)?.contentOrNull == "turn_started"
    }?.sequence
    val baseline = localWorkGoalBaselineForTurn(originalGoal, goal, start)?.let { previous ->
        if (originalGoal == null) previous else localWorkGoalBaselineAfterVerifiedToolUpdates(
            previous, goal, sourceRunId, start, binding.eventLog)
    }
    val evidence = localWorkCompletionEvidence(work,
        localWorkDeliveryEvidence(binding.eventLog, goal, workspaceRoot))
    // Reuse the same evidence that the Goal command and final-reply guard consume.
    // Reviewer approval cannot replace a missing file hash or failed real test result.
    val integration = decideLocalWorkIntegration(evidence)
    val conditions = LocalWorkGoalFollowUpConditions(
        originalGoal = baseline,
        currentGoal = goal,
        disposition = assessLocalWorkGoal(work, evidence).disposition,
        planMode = work.planMode,
        hadSuccessfulTool = hadSuccessfulTool,
        hadToolFailure = hadToolFailure,
        runtimeHealthy = binding.state.value.error == null,
        cancelled = binding.runHandle.cancellationRequested,
        pendingInputs = binding.runHandle.pendingInputs.size(),
        steeringUnchanged = binding.steeringRevision.value == originalSteeringRevision,
        completedContinuations = binding.automaticContinuationCount,
    )
    val candidate = decideLocalWorkGoalDrive(conditions,
        integration.readyForGoalAdmission &&
            (goal.artifactPaths.isNotEmpty() || goal.verificationCommands.isNotEmpty()))
    val result = when (candidate) {
        LocalWorkGoalDriveResult.COMPLETED ->
            if (completeGoal(goal)) LocalWorkGoalDriveResult.COMPLETED else LocalWorkGoalDriveResult.STALE
        LocalWorkGoalDriveResult.CONTINUE_QUEUED ->
            if (queueWorkGoalProgressFollowUp(binding, baseline, sourceRunId, sourceMessageId,
                    originalSteeringRevision, hadSuccessfulTool, hadToolFailure, workspaceRoot)) {
                LocalWorkGoalDriveResult.CONTINUE_QUEUED
            } else LocalWorkGoalDriveResult.PARTIAL
        else -> candidate
    }
    if (result != LocalWorkGoalDriveResult.IDLE) {
        binding.eventLog.append("goal/driver", buildJsonObject {
            put("goal_id", goal.id)
            put("goal_revision", goal.revision)
            put("source_run_id", sourceRunId)
            put("result", result.name.lowercase())
            put("integration_disposition", integration.disposition.name.lowercase())
            put("integration_review_pending", integration.reviewPendingCount)
            put("integration_review_rejected", integration.reviewRejectedCount)
            put("integration_machine_evidence_missing", integration.missingMachineEvidence.size)
            put("verification_level", when {
                goal.artifactPaths.isNotEmpty() && goal.verificationCommands.isNotEmpty() -> "artifact_and_execution"
                goal.artifactPaths.isNotEmpty() -> "artifact"
                goal.verificationCommands.isNotEmpty() -> "execution"
                else -> "declared_only"
            })
        })
    }
    return result
}
