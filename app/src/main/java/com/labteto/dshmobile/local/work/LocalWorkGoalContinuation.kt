package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.DEFAULT_AGENT_CONTINUATION_POLICY
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import java.io.File
import java.security.MessageDigest
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Work-owned, bounded follow-through after a real tool-progress turn. No new goal ledger.
 *
 * An autonomous follow-up is a new checkpoint-addressed request, never a replay of the
 * previous tool or provider request. The actual admission happens inside the Work run
 * registry's owner lock, just before it claims the next durable inbox item.
 */
internal data class LocalWorkGoalFollowUpConditions(
    val originalGoal: LocalGoal?,
    val currentGoal: LocalGoal?,
    val disposition: LocalWorkGoalDisposition,
    val planMode: Boolean,
    val hadSuccessfulTool: Boolean,
    val hadToolFailure: Boolean,
    val runtimeHealthy: Boolean = true,
    val cancelled: Boolean,
    val pendingInputs: Int,
    val steeringUnchanged: Boolean,
    val completedContinuations: Int,
)

internal fun canAutomaticallyFollowUpWorkGoal(
    conditions: LocalWorkGoalFollowUpConditions,
): Boolean {
    val original = conditions.originalGoal ?: return false
    val current = conditions.currentGoal ?: return false
    return original.id.isNotBlank() &&
        current == original &&
        current.status == "active" &&
        conditions.disposition == LocalWorkGoalDisposition.NEEDS_EXECUTION &&
        !conditions.planMode &&
        conditions.hadSuccessfulTool &&
        !conditions.hadToolFailure &&
        conditions.runtimeHealthy &&
        !conditions.cancelled &&
        conditions.pendingInputs == 0 &&
        conditions.steeringUnchanged &&
        conditions.completedContinuations < DEFAULT_AGENT_CONTINUATION_POLICY.maxContinuations
}


/**
 * Authoritative tool facts can outlive a crashed turn. Before admitting autonomous follow-through,
 * ensure that no execution belonging to this goal has an unknown side effect or a started tool
 * without a durable result. A later model response cannot settle a tool merely by claiming it did.
 *
 * Explicit reconciliation is a separate Shared Tool responsibility; pending/unknown effects
 * conservatively block automatic continuation until that authority can prove the outcome.
 */
internal fun hasUnresolvedWorkGoalToolEffects(
    eventLog: LocalSessionEventLog,
    goal: LocalGoal,
): Boolean {
    val callIds = mutableSetOf<String>()
    val started = mutableSetOf<String>()
    val settled = mutableSetOf<String>()
    var unknown = false
    val goalStartSequence = goal.startedAtSequence.takeIf { it >= 0L } ?: -1L
    for (event in eventLog.snapshotAfter(goalStartSequence)) {
        val data = event.data
        val callId = (data["id"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
        when (event.type) {
            "tool/call" -> {
                if ((data["goal_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == goal.id) {
                    callIds.add(callId)
                }
            }
            "tool/execution-started" -> {
                val attributes = data["attributes"] as? JsonObject
                if ((attributes?.get("goal_id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull == goal.id ||
                    callId in callIds) {
                    callIds.add(callId)
                    started.add(callId)
                }
            }
            "tool/result" -> {
                if (callId !in callIds) continue
                settled.add(callId)
                val code = (data["error_code"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val sideEffect = (data["side_effect"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                val isError = (data["is_error"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
                if (code == "TOOL_OUTCOME_UNKNOWN" || (isError && sideEffect == "possible")) {
                    unknown = true
                }
            }
        }
    }
    return unknown || started.any { it !in settled }
}

/** Content-addressed identity for a Work goal including its acceptance contract. */
internal fun localWorkGoalFingerprint(goal: LocalGoal): String {
    val content = Json.encodeToString(LocalGoal.serializer(), goal)
    return MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Re-check after durable inbox replay and before any model/tool action can start. */
internal fun isCurrentWorkGoalContinuation(
    continuation: com.labteto.dshmobile.harness.agent.AgentContinuationRef?,
    work: LocalWorkState,
): Boolean {
    if (continuation?.failureCode != WORK_GOAL_PROGRESS_CONTINUATION) return true
    val goal = work.goal ?: return false
    return !work.planMode && goal.status == "active" &&
        continuation.goalId?.isNotBlank() == true &&
        continuation.goalId == goal.id &&
        continuation.goalFingerprint?.isNotBlank() == true &&
        continuation.goalFingerprint == localWorkGoalFingerprint(goal) &&
        work.pendingApproval == null && work.pendingQuestion == null &&
        !work.team.rebuilding && work.team.failure.isNullOrBlank() &&
        work.team.activeMemberCountForContinuation() == 0
}

private fun LocalAgentTeamUiState.activeMemberCountForContinuation(): Int =
    members.count { it.phase == "provisioning" || it.activity in setOf("running", "stopping") } +
        pendingMessageCount + members.sumOf { it.pendingMessageCount } +
        members.count { it.awaitingReview }

/**
 * Run Owner calls this for each *actual* dequeued input, inside the durable poll callback.
 * A newly accepted user command invalidates a still-pending autonomous Goal follow-up.
 */
internal fun shouldDiscardWorkGoalContinuation(
    input: com.labteto.dshmobile.harness.agent.QueuedAgentInput,
    remaining: List<com.labteto.dshmobile.harness.agent.QueuedAgentInput>,
    work: LocalWorkState,
): Boolean =
    input.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.WORK_CONTINUATION &&
        input.continuation?.failureCode == WORK_GOAL_PROGRESS_CONTINUATION &&
        (remaining.any { it.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.USER } ||
            !isCurrentWorkGoalContinuation(input.continuation, work))

internal const val WORK_GOAL_PROGRESS_CONTINUATION = "WORK_GOAL_PROGRESS"

internal const val INTERNAL_WORK_GOAL_PROGRESS_PROMPT =
    "上一轮工具已有真实进展，但当前工作目标仍存在未完成事项。先读取当前目标、任务与" +
        "最新工具回执，核对已产生的真实成果，只继续尚未完成的工作；不得重复已结算副作用。" +
        "遇到未知工具结果、缺少授权或没有新的可验证进展时停止并说明阻塞原因。"

/**
 * Called only by the current Work run owner while holding its handoff lock.
 * The persisted inbox is authoritative; a queued user request always takes precedence.
 */
internal fun queueWorkGoalProgressFollowUp(
    binding: LocalWorkRunBinding,
    originalGoal: LocalGoal?,
    sourceRunId: String,
    sourceMessageId: String?,
    originalSteeringRevision: Long,
    hadSuccessfulTool: Boolean,
    hadToolFailure: Boolean,
    workspaceRoot: File,
): Boolean {
    val work = binding.state.value.work
    val goal = work.goal
    // Resolve the same evidence contract used by Goal completion; never trust model prose.
    val assessment = assessLocalWorkGoal(
        work,
        goal?.let {
            localWorkCompletionEvidence(work, localWorkDeliveryEvidence(binding.eventLog, it, workspaceRoot))
        } ?: localWorkCompletionEvidence(work),
    )
    val conditions = LocalWorkGoalFollowUpConditions(
        originalGoal = originalGoal,
        currentGoal = goal,
        disposition = assessment.disposition,
        planMode = work.planMode,
        hadSuccessfulTool = hadSuccessfulTool,
        hadToolFailure = hadToolFailure,
        runtimeHealthy = binding.state.value.error == null,
        cancelled = binding.runHandle.cancellationRequested,
        pendingInputs = binding.runHandle.pendingInputs.size(),
        steeringUnchanged = binding.steeringRevision.value == originalSteeringRevision,
        completedContinuations = binding.automaticContinuationCount,
    )
    if (!canAutomaticallyFollowUpWorkGoal(conditions)) return false
    // Even a different, successful tool receipt cannot certify an older unknown mutation.
    if (hasUnresolvedWorkGoalToolEffects(binding.eventLog, requireNotNull(goal))) return false

    val resume = LocalWorkResumeCommand(
        sourceRunId = sourceRunId,
        checkpointSequence = binding.eventLog.latestSequence(),
        failureCode = WORK_GOAL_PROGRESS_CONTINUATION,
        index = binding.automaticContinuationCount + 1,
        sourceMessageId = sourceMessageId,
        prompt = INTERNAL_WORK_GOAL_PROGRESS_PROMPT,
        goalId = requireNotNull(goal).id,
        goalFingerprint = localWorkGoalFingerprint(goal),
    )
    val input = resume.toQueueInput()
    val accepted = binding.runHandle.pendingInputs.offerIfEmpty(input) {
        binding.eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            encodeLocalAgentInboxEvent(
                action = "work-goal-progress-queued",
                pending = binding.runHandle.pendingInputs.snapshot(),
                affected = listOf(input),
            ),
        )
    }
    if (!accepted) return false
    binding.automaticContinuationCount = resume.index
    binding.continuationParentRunId = sourceRunId
    binding.state.update { state ->
        state.copy(kernel = state.kernel.copy(queuedInputCount = binding.runHandle.pendingInputs.size()))
    }
    return true
}
