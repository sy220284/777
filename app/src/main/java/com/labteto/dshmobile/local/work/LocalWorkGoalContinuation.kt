package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.DEFAULT_AGENT_CONTINUATION_POLICY
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import java.io.File
import kotlinx.coroutines.flow.update

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
        !conditions.cancelled &&
        conditions.pendingInputs == 0 &&
        conditions.steeringUnchanged &&
        conditions.completedContinuations < DEFAULT_AGENT_CONTINUATION_POLICY.maxContinuations
}

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
        cancelled = binding.runHandle.cancellationRequested,
        pendingInputs = binding.runHandle.pendingInputs.size(),
        steeringUnchanged = binding.steeringRevision.value == originalSteeringRevision,
        completedContinuations = binding.automaticContinuationCount,
    )
    if (!canAutomaticallyFollowUpWorkGoal(conditions)) return false

    val resume = LocalWorkResumeCommand(
        sourceRunId = sourceRunId,
        checkpointSequence = binding.eventLog.latestSequence(),
        failureCode = WORK_GOAL_PROGRESS_CONTINUATION,
        index = binding.automaticContinuationCount + 1,
        sourceMessageId = sourceMessageId,
        prompt = INTERNAL_WORK_GOAL_PROGRESS_PROMPT,
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
