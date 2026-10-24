package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.work.improvement.WorkImprovementAdvice
import com.labteto.dshmobile.local.work.improvement.workGoalComparisonKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkImprovementGuidanceTest {
    private val goal = LocalGoal(
        description = "修复资源索引", id = "goal-1", startedAtSequence = 10L,
        artifactPaths = listOf("app/src/main/Index.kt"),
        verificationCommands = listOf("./gradlew :app:testDebugUnitTest"),
    )
    private val advice = WorkImprovementAdvice(
        id = "advice-1", sourceSession = "earlier-session",
        sourceRunId = "run-old", sourceTaskRunId = "task-old",
        goalSignature = workGoalComparisonKey(goal),
        projectId = "project", model = "test-model", profileId = "profile",
        routeFingerprint = "route", category = "workflow", cue = "index",
        guidance = "先测试恢复日志再改投影", baselineToolCalls = 4,
        baselineToolErrors = 0, baselineTestVerified = true, active = false,
    )
    private fun render(
        candidate: WorkImprovementAdvice? = advice,
        currentGoal: LocalGoal? = goal,
        admittedGoalId: String? = goal.id,
        admittedRevision: Long? = 4L,
        nowRevision: Long = 4L,
        project: String? = "project",
        model: String = "test-model",
        profile: String? = "profile",
        route: String? = "route",
    ): String = workImprovementGuidanceForStep(candidate, currentGoal, admittedGoalId,
        admittedRevision, nowRevision, project, model, profile, route)

    @Test fun eligibleAdviceIsRequestOnlyAndKeepsUserApprovalRules() {
        val pending = render()
        assertTrue(pending.contains("待验证实验候选"))
        assertTrue(pending.contains("先测试恢复日志"))
        assertTrue(pending.contains("用户要求与已有权限/验收规则优先"))
        val accepted = render(candidate = advice.copy(active = true))
        assertTrue(accepted.contains("已采纳方法"))
        assertFalse(accepted.contains("待验证实验候选"))
    }

    @Test fun goalRevisionSteeringOrRouteChangesCannotInheritOldAdvice() {
        assertEquals("", render(currentGoal = goal.copy(id = "new-goal")))
        assertEquals("", render(currentGoal = goal.copy(description = "一个不同目标")))
        assertEquals("", render(currentGoal = goal.copy(artifactPaths = listOf("other.kt"))))
        assertEquals("", render(admittedGoalId = "old-goal"))
        assertEquals("", render(nowRevision = 5L))
        assertEquals("", render(admittedRevision = null))
        assertEquals("", render(project = "other-project"))
        assertEquals("", render(model = "another-model"))
        assertEquals("", render(profile = "other-profile"))
        assertEquals("", render(route = "another-route"))
    }

    @Test fun missingGoalOrMissingAdviceCannotCreateUnverifiedGuidance() {
        assertEquals("", render(currentGoal = null))
        assertEquals("", render(candidate = null))
    }
}
