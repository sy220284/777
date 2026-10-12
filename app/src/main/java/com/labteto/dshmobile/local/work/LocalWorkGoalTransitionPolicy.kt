package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.harness.state.RuntimeStateTransition
import com.labteto.dshmobile.harness.state.RuntimeStateTransitionPolicy
import com.labteto.dshmobile.harness.state.acceptRuntimeStateTransition
import com.labteto.dshmobile.harness.state.rejectRuntimeStateTransition
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun resolveWorkGoalTransition(
    current: LocalGoal,
    candidate: LocalGoal,
    work: LocalWorkState,
    deliveryEvidence: LocalWorkDeliveryEvidence? = null,
): RuntimeStateTransition<LocalGoal> =
    RuntimeStateTransitionPolicy<LocalGoal> { runtimeOwned, proposed ->
        // Check the proposed goal status, not its previous status: a blocked goal must
        // be allowed to become completed when all actual obligations have cleared.
        val evidence = localWorkCompletionEvidence(work.copy(goal = proposed), deliveryEvidence)
        if (proposed.status == "completed" && !evidence.readyForGoalCompletion) {
            rejectRuntimeStateTransition(
                runtimeOwned,
                "目标尚不能标记完成：" + evidence.userReasons.joinToString("；"),
            )
        } else {
            acceptRuntimeStateTransition(proposed)
        }
    }.resolve(current, candidate)


internal fun resolveWorkGoalUpdate(
    snapshot: LocalWorkState,
    status: String,
    note: String?,
    eventLog: LocalSessionEventLog,
): LocalGoal {
    val current = snapshot.goal ?: error("当前会话没有目标")
    val candidate = current.copy(status = status, note = note?.take(2_000))
    val delivery = if (status == "completed") localWorkDeliveryEvidence(eventLog, candidate.description) else null
    val transition = resolveWorkGoalTransition(current, candidate, snapshot, delivery)
    if (!transition.accepted) {
        eventLog.append("goal/transition-rejected", buildJsonObject {
            put("from_status", current.status)
            put("to_status", status)
            val evidence = localWorkCompletionEvidence(snapshot.copy(goal = candidate), delivery)
            put("open_todos", evidence.openTodos)
            put("open_team_tasks", evidence.openTeamTasks)
            put("active_team_members", evidence.activeTeamMembers)
            put("pending_team_messages", evidence.pendingTeamMessages)
            put("team_rebuilding", evidence.teamRebuilding)
            put("pending_approval", evidence.pendingApproval)
            put("pending_question", evidence.pendingQuestion)
            put("artifact_evidence", delivery?.artifactCallId ?: "")
            put("test_evidence", delivery?.testCallId ?: "")
            transition.reason?.let { put("reason", it) }
        })
        error(transition.reason ?: "目标状态变更被运行时拒绝")
    }
    return transition.value
}
