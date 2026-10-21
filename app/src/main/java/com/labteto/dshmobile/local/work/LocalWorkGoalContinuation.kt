package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.DEFAULT_AGENT_CONTINUATION_POLICY
import com.labteto.dshmobile.local.agent.LOCAL_AGENT_INBOX_EVENT_TYPE
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import java.io.File
import java.security.MessageDigest
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

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
    /** Consecutive turns with no change in Work-owned, verifiable task/artifact facts. */
    val stalledRounds: Int = 0,
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
        conditions.stalledRounds < DEFAULT_AGENT_CONTINUATION_POLICY.maxContinuations &&
        conditions.completedContinuations < MAX_GOAL_CONTINUATION_ROUNDS
}

internal const val MAX_GOAL_CONTINUATION_ROUNDS = 96

/**
 * Work-domain progress proof: outstanding obligation identities, finished Team/Todo
 * facts, and the *actual bytes* of declared artifact files. A new tool call ID,
 * model answer or elapsed time cannot change this proof by itself.
 *
 * The proof is logged in the canonical Session EventLog's inbox admission fact.
 * No separate mutable progress database or duplicate scheduler is created.
 */
internal fun workGoalProgressFingerprint(
    work: LocalWorkState,
    evidence: LocalWorkCompletionEvidence,
    workspaceRoot: File,
): String {
    val goal = requireNotNull(work.goal)
    val material = buildString {
        append(goal.id)
        append('|')
        append(localWorkGoalFingerprint(goal))
        append('|')
        work.todos.forEach { append(it.toString()).append('\n') }
        work.team.tasks.forEach { append(it.toString()).append('\n') }
        work.team.members.forEach { append(it.phase).append(':').append(it.activity).append('\n') }
        append(evidence.openTodos).append(':').append(evidence.openTeamTasks).append(':')
            .append(evidence.activeTeamMembers).append(':').append(evidence.failedTeamMembers)
        evidence.deliveryEvidence?.let {
            append(it.artifactCallId).append(':').append(it.testCallId).append(':')
            append(it.missing.joinToString("|"))
        }
        goal.artifactPaths.forEach { artifact ->
            append(artifact).append('=')
                .append(localWorkFileSha256(workspaceRoot, artifact) ?: "missing")
                .append('\n')
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun nextGoalStalledRounds(
    previousProof: String?,
    previousStalledRounds: Int,
    currentProof: String,
): Int = when {
    previousProof == null || previousProof != currentProof -> 0
    else -> (previousStalledRounds + 1).coerceAtMost(MAX_GOAL_CONTINUATION_ROUNDS)
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
    executingToolId: String? = null,
): Boolean {
    // Share SessionRecovery's typed tool-attempt reducer. Each event is
    // consumed once, from this Goal's first persisted sequence onwards.
    val ledger = ToolAttemptLedger()
    val goalStartSequence = goal.startedAtSequence.takeIf { it >= 0L } ?: -1L
    eventLog.forEachAfter(goalStartSequence) { event ->
        ledger.observe(event.type, event.data)
    }
    return goal.id.isNotBlank() && ledger.snapshot().any {
        it.goalId == goal.id && it.unresolvedExternalEffect &&
            (executingToolId == null || it.executionId != executingToolId)
    }
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
    eventLog: LocalSessionEventLog? = null,
): Boolean {
    // A user-facing form blocks *every* autonomous continuation, including retry and
    // recovery ones. Only a subsequent real user send can release its durable fence.
    if (continuation != null && eventLog != null &&
        hasOutstandingWorkReplyForm(eventLog)) return false
    if (continuation?.failureCode != WORK_GOAL_PROGRESS_CONTINUATION) return true
    val goal = work.goal ?: return false
    return !work.planMode && goal.status == "active" &&
        continuation.goalId?.isNotBlank() == true &&
        continuation.goalId == goal.id &&
        (continuation.goalRevision?.let { it == goal.revision } ?: (
            continuation.goalFingerprint?.isNotBlank() == true &&
                continuation.goalFingerprint == localWorkGoalFingerprint(goal))) &&
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
    eventLog: LocalSessionEventLog? = null,
): Boolean =
    input.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.WORK_CONTINUATION &&
        (!isCurrentWorkGoalContinuation(input.continuation, work, eventLog) ||
            (input.continuation?.failureCode in setOf(WORK_GOAL_PROGRESS_CONTINUATION, WORK_OUTPUT_LIMIT_CONTINUATION) &&
                remaining.any { it.kind == com.labteto.dshmobile.harness.agent.QueuedAgentInputKind.USER }))

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
    if (hasOutstandingWorkReplyForm(binding.eventLog)) return false
    // Resolve the same evidence contract used by Goal completion; never trust model prose.
    val assessment = assessLocalWorkGoal(
        work,
        goal?.let {
            localWorkCompletionEvidence(work, localWorkDeliveryEvidence(binding.eventLog, it, workspaceRoot))
        } ?: localWorkCompletionEvidence(work),
    )
    val proof = goal?.let { workGoalProgressFingerprint(work, assessment.evidence, workspaceRoot) }
    val goalIdentity = goal?.let(::localWorkGoalFingerprint)
    val previous = if (goal == null) null else binding.eventLog.latestMatching(
        setOf(LOCAL_AGENT_INBOX_EVENT_TYPE),
    ) { data ->
        (data["action"] as? JsonPrimitive)?.contentOrNull == "work-goal-progress-queued" &&
            (data["goal_id"] as? JsonPrimitive)?.contentOrNull == goal.id &&
            ((data["goal_revision"] as? JsonPrimitive)?.longOrNull == goal.revision ||
                (data["goal_fingerprint"] as? JsonPrimitive)?.contentOrNull == goalIdentity)
    }
    val prevProof = (previous?.data?.get("goal_progress_proof") as? JsonPrimitive)?.contentOrNull
    val prevStall = (previous?.data?.get("goal_stalled_rounds") as? JsonPrimitive)?.intOrNull ?: 0
    val stalled = nextGoalStalledRounds(prevProof, prevStall, proof.orEmpty())
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
        stalledRounds = stalled,
    )
    if (!canAutomaticallyFollowUpWorkGoal(conditions)) return false
    // Even a different, successful tool receipt cannot certify an older unknown mutation.
    if (hasUnresolvedWorkGoalToolEffects(binding.eventLog, requireNotNull(goal))) return false
    // A process crash may leave a graph without its terminal event. Its nodes
    // must be reconciled by recovery, never blindly spawned again by GoalDriver.
    if (hasUnsettledWorkGoalTaskGraphs(binding.eventLog, goal)) return false

    val resume = LocalWorkResumeCommand(
        sourceRunId = sourceRunId,
        checkpointSequence = binding.eventLog.latestSequence(),
        failureCode = WORK_GOAL_PROGRESS_CONTINUATION,
        index = binding.automaticContinuationCount + 1,
        sourceMessageId = sourceMessageId,
        prompt = INTERNAL_WORK_GOAL_PROGRESS_PROMPT,
        goalId = requireNotNull(goal).id,
        goalRevision = goal.revision,
    )
    val input = resume.toQueueInput()
    val accepted = binding.runHandle.pendingInputs.offerIfEmpty(input) {
        binding.eventLog.append(
            LOCAL_AGENT_INBOX_EVENT_TYPE,
            buildJsonObject {
                encodeLocalAgentInboxEvent(
                    action = "work-goal-progress-queued",
                    pending = binding.runHandle.pendingInputs.snapshot(),
                    affected = listOf(input),
                ).forEach { (key, value) -> put(key, value) }
                put("goal_id", requireNotNull(goal).id)
                put("goal_revision", goal.revision)
                put("goal_progress_proof", requireNotNull(proof))
                put("goal_stalled_rounds", stalled)
            },
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
