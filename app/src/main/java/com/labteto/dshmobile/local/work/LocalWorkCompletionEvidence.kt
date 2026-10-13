package com.labteto.dshmobile.local.work

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One Work-owned view of outstanding obligations. Turn completion only means the model
 * stopped speaking; it is never sufficient evidence that the user's goal was delivered.
 *
 * The same evaluator drives Goal state admission and final-answer verification. This is a
 * projection over the existing Work state, NOT a second task owner or mutable ledger.
 */
internal data class LocalWorkCompletionEvidence(
    val openTodos: Int,
    val openTeamTasks: Int,
    val activeTeamMembers: Int,
    val pendingTeamMessages: Int,
    val failedTeamMembers: Int,
    val awaitingReviewMembers: Int,
    val teamFailure: Boolean,
    val teamRebuilding: Boolean,
    val blockedGoal: Boolean,
    val pendingApproval: Boolean,
    val pendingQuestion: Boolean,
    val deliveryEvidence: LocalWorkDeliveryEvidence? = null,
) {
    val findings: List<String> get() = buildList {
        if (openTodos > 0) add("完成声明与未完成任务清单冲突")
        if (openTeamTasks > 0) add("完成声明与未完成 Agent Team 任务冲突")
        if (activeTeamMembers > 0) add("完成声明与仍在运行的 Agent Team 成员冲突")
        if (pendingTeamMessages > 0) add("完成声明与待投递 Agent Team 消息冲突")
        if (failedTeamMembers > 0) add("完成声明与失败的 Agent Team 成员冲突")
        if (awaitingReviewMembers > 0) add("完成声明与仍待核验的 Agent Team 成果冲突")
        if (teamFailure) add("完成声明与 Agent Team 异常状态冲突")
        if (teamRebuilding) add("完成声明与正在恢复的 Agent Team 状态冲突")
        if (blockedGoal) add("完成声明与阻塞目标状态冲突")
        if (pendingApproval) add("完成声明与待审批操作冲突")
        if (pendingQuestion) add("完成声明与待回答问题冲突")
        deliveryEvidence?.missing?.forEach { add("完成声明缺少正向验收证据：$it") }
    }

    val userReasons: List<String> get() = buildList {
        if (openTodos > 0) add("仍有 $openTodos 项任务未完成")
        if (openTeamTasks > 0) add("Agent Team 仍有 $openTeamTasks 项任务未完成")
        if (activeTeamMembers > 0) add("仍有 $activeTeamMembers 个智能体在执行")
        if (pendingTeamMessages > 0) add("仍有 $pendingTeamMessages 条 Team 消息待投递")
        if (failedTeamMembers > 0) add("仍有 $failedTeamMembers 个 Agent Team 成员失败")
        if (awaitingReviewMembers > 0) add("仍有 $awaitingReviewMembers 个 Agent Team 成果待核验")
        if (teamFailure) add("Agent Team 当前存在异常状态")
        if (teamRebuilding) add("Agent Team 正在恢复状态")
        if (blockedGoal) add("目标仍处于阻塞状态")
        if (pendingApproval) add("还有工具操作等待审批")
        if (pendingQuestion) add("还有待回答的问题")
        deliveryEvidence?.missing?.let(::addAll)
    }

    val readyForGoalCompletion: Boolean get() = findings.isEmpty()
}

internal fun localWorkCompletionEvidence(
    work: LocalWorkState,
    deliveryEvidence: LocalWorkDeliveryEvidence? = null,
): LocalWorkCompletionEvidence {
    val team = work.team
    val failedMemberCount = team.failedMemberCount.takeIf { count ->
        count > 0 && (team.tasks.isEmpty() ||
            team.tasks.any { it.status !in setOf("completed", "deleted") })
    } ?: 0
    return LocalWorkCompletionEvidence(
        openTodos = work.todos.count { it.status == "pending" || it.status == "in_progress" },
        openTeamTasks = team.tasks.count { it.status == "pending" || it.status == "in_progress" },
        activeTeamMembers = team.members.count {
            it.phase == "provisioning" || it.activity == "running" || it.activity == "stopping"
        },
        pendingTeamMessages = maxOf(
            team.pendingMessageCount,
            team.members.sumOf { it.pendingMessageCount },
        ),
        failedTeamMembers = failedMemberCount,
        awaitingReviewMembers = team.returnedMemberCount,
        teamFailure = !team.failure.isNullOrBlank(),
        teamRebuilding = team.rebuilding,
        blockedGoal = work.goal?.status == "blocked",
        pendingApproval = work.pendingApproval != null,
        pendingQuestion = work.pendingQuestion != null,
        deliveryEvidence = deliveryEvidence,
    )
}

/**
 * Canonical, read-only Work completion fact projection.
 *
 * Goal transition admission, final reply quality and diagnostics consume exactly this
 * snapshot. No new persistence table, mutable status owner or model-derived success state.
 */
internal fun LocalWorkCompletionEvidence.toDiagnosticJson(): JsonObject = buildJsonObject {
    put("ready_for_goal_completion", readyForGoalCompletion)
    put("open_todos", openTodos)
    put("open_team_tasks", openTeamTasks)
    put("active_team_members", activeTeamMembers)
    put("pending_team_messages", pendingTeamMessages)
    put("failed_team_members", failedTeamMembers)
    put("awaiting_review_members", awaitingReviewMembers)
    put("team_failure", teamFailure)
    put("team_rebuilding", teamRebuilding)
    put("blocked_goal", blockedGoal)
    put("pending_approval", pendingApproval)
    put("pending_question", pendingQuestion)
    put("delivery_missing", JsonArray(deliveryEvidence?.missing.orEmpty().map(::JsonPrimitive)))
    put("user_reasons", JsonArray(userReasons.map(::JsonPrimitive)))
}
