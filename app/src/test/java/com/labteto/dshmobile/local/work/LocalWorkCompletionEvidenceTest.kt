package com.labteto.dshmobile.local.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkCompletionEvidenceTest {
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
}
