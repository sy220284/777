package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.improvement.WorkImprovementAdvice
import com.labteto.dshmobile.local.work.improvement.workGoalComparisonKey

/**
 * Request-local, non-authoritative prompt projection from an event-backed Work strategy.
 *
 * Any Goal, steering revision, model, credential route or project switch invalidates
 * the suggestion. Never append this text to durable history or grant tool permissions.
 */
internal fun workImprovementGuidanceForStep(
    advice: WorkImprovementAdvice?,
    liveGoal: LocalGoal?,
    admittedGoalId: String?,
    admittedSteeringRevision: Long?,
    currentSteeringRevision: Long,
    projectId: String?,
    model: String,
    profileId: String?,
    routeFingerprint: String?,
): String {
    val validAdvice = advice?.takeIf { selected ->
        liveGoal != null && liveGoal.id == admittedGoalId &&
            admittedSteeringRevision == currentSteeringRevision &&
            model == selected.model &&
            (profileId ?: "") == selected.profileId &&
            (routeFingerprint ?: "") == selected.routeFingerprint &&
            (projectId ?: "") == selected.projectId &&
            workGoalComparisonKey(liveGoal) == selected.goalSignature
    } ?: return ""
    return "\n\n[可选工作方法建议：" + validAdvice.id + "]\n来自历史工作证据的" +
        (if (validAdvice.active) "已采纳方法" else "待验证实验候选") +
        "；仅作为辅助建议，用户要求与已有权限/验收规则优先。\n" +
        validAdvice.guidance
}
