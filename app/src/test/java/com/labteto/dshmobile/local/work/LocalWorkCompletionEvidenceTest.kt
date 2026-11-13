package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkCompletionEvidenceTest {

    @Test
    fun blockedWorkflowRejectsPrematureGoalCompletionAndPreventsAutonomousResume() {
        val goal = LocalGoal("检查并交付", id = "goal-graph", status = "active")
        val work = LocalWorkState(
            goal = goal,
            todos = listOf(LocalTodoItem("图结果已处理", "completed")),
            workflowProgress = LocalWorkflowProgress(
                sessionId = "session-graph",
                stage = "受阻",
                task = "依赖节点",
                completed = 2,
                total = 3,
                blockedReason = "子任务未通过验收",
                needsUserAction = true,
            ),
        )
        val evidence = localWorkCompletionEvidence(work)
        assertFalse(evidence.readyForGoalCompletion)
        assertTrue(evidence.toDiagnosticJson()["blocked_workflow"].toString() == "true")
        assertTrue(evidence.userReasons.any { it.contains("工作流") })
        assertTrue(assessLocalWorkGoal(work).disposition == LocalWorkGoalDisposition.BLOCKED)
        assertFalse(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), work).accepted)
        val afterResolution = work.copy(workflowProgress = null)
        assertTrue(localWorkCompletionEvidence(afterResolution).readyForGoalCompletion)
    }

    @Test
    fun reviewerVerdictFromDurableTeamTaskMustBeAcceptedBeforeGoalCanSettle() {
        fun task(status: String) = LocalAgentTeamTaskUiState(
            id = "task-verify", subject = "独立验收", description = "",
            status = "completed", revision = 3,
            completionResultId = "worker-proof",
            completionEvidenceSequence = 18,
            independentReviewStatus = status,
        )
        val goal = LocalGoal("交付独立验证结果", id = "goal-reviewed")
        for (status in listOf("pending", "review_invalid", "evidence_invalid", "root_superseded")) {
            val work = LocalWorkState(goal = goal,
                team = LocalAgentTeamUiState(tasks = listOf(task(status))))
            val evidence = localWorkCompletionEvidence(work)
            assertFalse(evidence.readyForGoalCompletion)
            assertTrue(evidence.unreviewedTeamResults == 1)
            assertFalse(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), work).accepted)
        }
        val rejected = LocalWorkState(goal = goal,
            team = LocalAgentTeamUiState(tasks = listOf(task("reviewer_rejected"))))
        assertTrue(localWorkCompletionEvidence(rejected).rejectedTeamResults == 1)
        assertFalse(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), rejected).accepted)

        val reviewed = LocalWorkState(goal = goal,
            team = LocalAgentTeamUiState(tasks = listOf(task("reviewer_approved"))))
        assertTrue(localWorkCompletionEvidence(reviewed).readyForGoalCompletion)
        assertTrue(resolveWorkGoalTransition(goal, goal.copy(status = "completed"), reviewed).accepted)
        // Legacy Task records without a durable result pointer remain readable.
        val legacy = reviewed.copy(team = LocalAgentTeamUiState(tasks = listOf(
            task("legacy_unknown").copy(completionResultId = null,
                completionEvidenceSequence = null),
        )))
        assertTrue(localWorkCompletionEvidence(legacy).readyForGoalCompletion)
    }

    @Test
    fun goalTransitionAndDeliveryUseOneDiagnosticFactProjection() {
        val work = LocalWorkState(
            goal = LocalGoal("核查交付", status = "blocked"),
            todos = listOf(LocalTodoItem("验证", "in_progress")),
            team = LocalAgentTeamUiState(rebuilding = true),
        )
        val evidence = localWorkCompletionEvidence(work)
        val diagnostic = evidence.toDiagnosticJson()
        assertFalse(diagnostic["ready_for_goal_completion"]!!.toString().toBoolean())
        assertTrue(diagnostic["open_todos"]!!.toString() == "1")
        assertTrue(diagnostic["team_rebuilding"]!!.toString() == "true")
        assertTrue(diagnostic["blocked_goal"]!!.toString() == "true")
        assertFalse(resolveWorkGoalTransition(
            requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"),
            work,
        ).accepted)
    }


    private fun teamMember(activity: String, resultReady: Boolean = false) =
        LocalAgentTeamMemberUiState(
            id = "member-1", jobId = "job-1", name = "reviewer",
            description = "审查", phase = "active", activity = activity,
            hasCurrentTaskResult = resultReady,
        )

    @Test
    fun completedTodosCannotMaskStillRunningTeamWork() {
        val work = LocalWorkState(
            goal = LocalGoal("完成所有验证"),
            todos = listOf(LocalTodoItem("静态检查", "completed")),
            team = LocalAgentTeamUiState(
                members = listOf(teamMember("running")),
                tasks = listOf(LocalAgentTeamTaskUiState(
                    id = "task-1", subject = "设备回归", description = "",
                    status = "in_progress",
                )),
            ),
        )
        val evidence = localWorkCompletionEvidence(work)
        val transition = resolveWorkGoalTransition(
            current = requireNotNull(work.goal),
            candidate = requireNotNull(work.goal).copy(status = "completed"),
            work = work,
        )
        val reported = LocalWorkCompletionClaimGuard.inspect("全部完成，可以交付。", work)
        assertFalse(evidence.readyForGoalCompletion)
        assertFalse(transition.accepted)
        assertTrue(reported.findings.contains("完成声明与未完成 Agent Team 任务冲突"))
        assertTrue(reported.findings.contains("完成声明与仍在运行的 Agent Team 成员冲突"))
    }

    @Test
    fun unverifiedReturnedAgentResultDoesNotCountAsGoalCompletion() {
        val work = LocalWorkState(
            goal = LocalGoal("完成验证"),
            team = LocalAgentTeamUiState(members = listOf(teamMember("dormant", resultReady = true))),
        )
        assertFalse(localWorkCompletionEvidence(work).readyForGoalCompletion)
        assertFalse(resolveWorkGoalTransition(
            requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"), work,
        ).accepted)
    }

    @Test
    fun previouslyBlockedGoalCanCompleteAfterRealBlockersAreGone() {
        val work = LocalWorkState(
            goal = LocalGoal("修复错误", status = "blocked"),
            todos = listOf(LocalTodoItem("复核并验收", "completed")),
        )
        val target = requireNotNull(work.goal).copy(status = "completed")
        assertTrue(resolveWorkGoalTransition(requireNotNull(work.goal), target, work).accepted)
        assertTrue(localWorkCompletionEvidence(work.copy(goal = target)).readyForGoalCompletion)
    }

    @Test
    fun teamProjectionRebuildIsVisibleAndDoesNotBecomeFalseSuccess() {
        val work = LocalWorkState(
            goal = LocalGoal("验证团队结果"),
            team = LocalAgentTeamUiState(rebuilding = true),
        )
        val evidence = localWorkCompletionEvidence(work)
        assertFalse(evidence.readyForGoalCompletion)
        assertTrue(evidence.userReasons.any { it.contains("正在恢复") })
        assertFalse(resolveWorkGoalTransition(
            requireNotNull(work.goal), requireNotNull(work.goal).copy(status = "completed"), work,
        ).accepted)
    }

    @Test
    fun staleFailureAfterSuccessfulReassignmentDoesNotBlockCompletion() {
        val work = LocalWorkState(
            goal = LocalGoal("验证任务"),
            team = LocalAgentTeamUiState(
                members = listOf(LocalAgentTeamMemberUiState(
                    id = "old", jobId = "old-job", name = "old", description = "",
                    phase = "failed", activity = "failed",
                )),
                tasks = listOf(LocalAgentTeamTaskUiState(
                    id = "task", subject = "已重分配并完成", description = "",
                    status = "completed",
                )),
            ),
        )
        assertTrue(localWorkCompletionEvidence(work).readyForGoalCompletion)
    }

    @Test
    fun pendingApprovalAndPendingQuestionHaveDifferentNonRunnableStates() {
        val goal = LocalGoal("继续实施", id = "g-interaction")
        val waiting = LocalWorkState(
            goal = goal,
            todos = listOf(LocalTodoItem("等待实际操作", "pending")),
            pendingApproval = com.labteto.dshmobile.local.interaction.LocalApproval(
                callId = "call-approval", toolName = "write_file", summary = "写入文件",
                arguments = "{}", access = "write",
            ),
        )
        val approval = assessLocalWorkGoal(waiting)
        assertTrue(approval.disposition == LocalWorkGoalDisposition.WAITING_APPROVAL)
        assertFalse(approval.hasRunnableRemainingWork)

        val question = assessLocalWorkGoal(waiting.copy(
            pendingApproval = null,
            pendingQuestion = com.labteto.dshmobile.local.interaction.LocalQuestion(
                callId = "call-question", question = "需要选择哪一种结果？",
            ),
        ))
        assertTrue(question.disposition == LocalWorkGoalDisposition.WAITING_USER)
        assertFalse(question.hasRunnableRemainingWork)
    }

    @Test
    fun turnCompletionAndGoalCompletionRemainDistinct() {
        val goal = LocalGoal("修复并验证", id = "g1", status = "active")
        val open = LocalWorkState(goal = goal, todos = listOf(LocalTodoItem("跑测试", "pending")))
        val assessment = assessLocalWorkGoal(open)
        assertTrue(assessment.hasRunnableRemainingWork)
        assertTrue(assessment.disposition == LocalWorkGoalDisposition.NEEDS_EXECUTION)
        assertTrue(assessment.toTurnEventFields()["goal_disposition"].toString().contains("needs_execution"))
        assertTrue(assessment.toTurnEventFields()["goal_outstanding_count"].toString() == "1")

        val cleared = open.copy(todos = listOf(LocalTodoItem("跑测试", "completed")))
        val ready = assessLocalWorkGoal(cleared)
        assertTrue(ready.disposition == LocalWorkGoalDisposition.READY_FOR_SETTLEMENT)
        assertFalse(ready.hasRunnableRemainingWork)
        assertTrue(assessLocalWorkGoal(cleared.copy(goal = goal.copy(status = "completed")))
            .disposition == LocalWorkGoalDisposition.COMPLETED)
    }

    @Test
    fun goalDispositionDoesNotAutoRunWhilePausedOrWaitingForApproval() {
        val goal = LocalGoal("准备构建", id = "g2")
        val open = LocalWorkState(goal = goal, todos = listOf(LocalTodoItem("执行构建", "pending")))
        assertTrue(assessLocalWorkGoal(open.copy(goal = goal.copy(status = "paused")))
            .disposition == LocalWorkGoalDisposition.PAUSED)
        assertTrue(assessLocalWorkGoal(open.copy(goal = goal.copy(status = "blocked")))
            .disposition == LocalWorkGoalDisposition.BLOCKED)
        val waitingTeam = open.copy(team = LocalAgentTeamUiState(
            members = listOf(teamMember("running"))))
        assertTrue(assessLocalWorkGoal(waitingTeam).disposition == LocalWorkGoalDisposition.WAITING_TEAM)
        assertFalse(assessLocalWorkGoal(waitingTeam).hasRunnableRemainingWork)
    }

    @Test
    fun goalDispositionCannotCompleteOnStaleArtifactOrNoGoal() {
        val legacy = LocalWorkState(todos = listOf(LocalTodoItem("旧任务", "pending")))
        assertTrue(assessLocalWorkGoal(legacy).disposition == LocalWorkGoalDisposition.NO_GOAL)
        val work = LocalWorkState(goal = LocalGoal("构建", status = "completed", id = "g3"))
        val delivery = LocalWorkDeliveryEvidence(
            needsArtifact = true,
            needsTest = true,
            unmetRequirements = listOf("验收命令对应的文件版本已改变"),
        )
        val snapshot = localWorkCompletionEvidence(work, delivery)
        val disposition = assessLocalWorkGoal(work, snapshot)
        assertTrue(disposition.disposition == LocalWorkGoalDisposition.NEEDS_EXECUTION)
        assertFalse(disposition.toTurnEventFields()["goal_ready_for_settlement"].toString().toBoolean())
    }

}
