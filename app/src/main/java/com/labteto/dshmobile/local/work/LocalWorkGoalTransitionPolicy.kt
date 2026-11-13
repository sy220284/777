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
        // Use the same Work evidence and typed disposition as real turn settlement.
        // The disposition itself is not a second Goal owner or a completion permission.
        val evidence = assessLocalWorkGoal(work.copy(goal = proposed),
            localWorkCompletionEvidence(work.copy(goal = proposed), deliveryEvidence)).evidence
        // Explicit Goal commands and autonomous GoalDriver share the same integration gate.
        // This projection joins Team reviewer status with real tool/file/test evidence.
        val integration = decideLocalWorkIntegration(evidence)
        if (proposed.status == "completed" && !integration.readyForGoalAdmission) {
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
    workspaceRoot: java.io.File? = null,
    executingToolId: String? = null,
): LocalGoal {
    val current = snapshot.goal ?: error("当前会话没有目标")
    val proposed = current.copy(status = status, note = note?.take(2_000))
    val candidate = if (proposed == current) current else proposed.copy(revision = Math.incrementExact(current.revision))
    val delivery = if (status == "completed") localWorkDeliveryEvidence(eventLog, current, workspaceRoot, executingToolId) else null
    val transition = resolveWorkGoalTransition(current, candidate, snapshot, delivery)
    if (!transition.accepted) {
        eventLog.append("goal/transition-rejected", buildJsonObject {
            put("from_status", current.status)
            put("to_status", status)
            val evidence = localWorkCompletionEvidence(snapshot.copy(goal = candidate), delivery)
            // Same snapshot used by final-answer validation; no second ad-hoc counter list.
            evidence.toDiagnosticJson().forEach { (key, value) -> put(key, value) }
            put("artifact_evidence", delivery?.artifactCallId ?: "")
            put("test_evidence", delivery?.testCallId ?: "")
            transition.reason?.let { put("reason", it) }
        })
        error(transition.reason ?: "目标状态变更被运行时拒绝")
    }
    return transition.value
}
