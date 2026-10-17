package com.labteto.dshmobile.local.work

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
    val start = if (originalGoal == null) binding.eventLog.latestMatching(
        setOf(com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT),
    ) { data ->
        (data["run_id"] as? JsonPrimitive)?.contentOrNull == sourceRunId &&
            (data["phase"] as? JsonPrimitive)?.contentOrNull == "turn_started"
    }?.sequence else null
    val baseline = localWorkGoalBaselineForTurn(originalGoal, goal, start)
    val evidence = localWorkCompletionEvidence(work,
        localWorkDeliveryEvidence(binding.eventLog, goal, workspaceRoot))
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
        goal.artifactPaths.isNotEmpty() || goal.verificationCommands.isNotEmpty())
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
