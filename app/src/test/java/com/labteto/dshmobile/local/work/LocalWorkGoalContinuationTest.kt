package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.agent.decodeLocalAgentInboxPending
import com.labteto.dshmobile.local.agent.encodeLocalAgentInboxEvent
import com.labteto.dshmobile.harness.agent.QueuedAgentInputKind
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalWorkGoalContinuationTest {
    private val goal = LocalGoal(
        description = "实现并验证代码",
        id = "goal-1",
        startedAtSequence = 12L,
        artifactPaths = listOf("src/Feature.kt"),
        verificationCommands = listOf("run-checks"),
    )

    private fun conditions() = LocalWorkGoalFollowUpConditions(
        originalGoal = goal,
        currentGoal = goal,
        disposition = LocalWorkGoalDisposition.NEEDS_EXECUTION,
        planMode = false,
        hadSuccessfulTool = true,
        hadToolFailure = false,
        cancelled = false,
        pendingInputs = 0,
        steeringUnchanged = true,
        completedContinuations = 0,
    )

    @Test
    fun realToolProgressWithOpenGoalCanScheduleOneMoreTurn() {
        assertTrue(canAutomaticallyFollowUpWorkGoal(conditions()))
    }

    @Test
    fun noToolProgressOrUncertainToolFailureNeverTriggersBlindReplay() {
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(hadSuccessfulTool = false)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(hadToolFailure = true)))
    }

    @Test
    fun userInputSteeringAndCancellationAlwaysOverrideAutonomousFollowUp() {
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(pendingInputs = 1)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(steeringUnchanged = false)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(cancelled = true)))
    }

    @Test
    fun changedOrMissingGoalAndNonRunningStatesCannotScheduleStaleWork() {
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(originalGoal = null)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(currentGoal = goal.copy(id = "new-goal"))))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(currentGoal = goal.copy(note = "user-edit"))))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(currentGoal = goal.copy(status = "completed"))))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(currentGoal = goal.copy(id = ""))))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            disposition = LocalWorkGoalDisposition.WAITING_USER)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            disposition = LocalWorkGoalDisposition.WAITING_APPROVAL)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            disposition = LocalWorkGoalDisposition.WAITING_TEAM)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            disposition = LocalWorkGoalDisposition.READY_FOR_SETTLEMENT)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(planMode = true)))
    }

    @Test
    fun sharedContinuationBoundPreventsRepeatedUnproductiveTurns() {
        assertTrue(canAutomaticallyFollowUpWorkGoal(conditions().copy(completedContinuations = 1)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(completedContinuations = 2)))
    }

    @Test
    fun goalFollowUpUsesExistingDurableResumeContractWithoutPretendingToBeUserInput() {
        val command = LocalWorkResumeCommand(
            sourceRunId = "work-run-1",
            checkpointSequence = 42L,
            failureCode = WORK_GOAL_PROGRESS_CONTINUATION,
            index = 1,
            sourceMessageId = "original-message",
            prompt = INTERNAL_WORK_GOAL_PROGRESS_PROMPT,
        )
        val queued = command.toQueueInput()
        assertEquals(QueuedAgentInputKind.WORK_CONTINUATION, queued.kind)
        assertEquals("", queued.memoryInput)
        assertEquals(INTERNAL_WORK_GOAL_PROGRESS_PROMPT, queued.content)
        assertEquals("system", queued.modelMessage?.get("role")?.jsonPrimitive?.content)
        assertEquals("work-run-1", queued.continuation?.sourceRunId)
        assertEquals("original-message", queued.continuation?.sourceMessageId)

        val snapshot = encodeLocalAgentInboxEvent("work-goal-progress-queued", listOf(queued))
        val replayed = decodeLocalAgentInboxPending(snapshot)
        assertNotNull(replayed)
        assertEquals(1, replayed!!.size)
        assertEquals(queued.kind, replayed.single().kind)
        assertEquals(command.id, replayed.single().id)
        assertEquals(WORK_GOAL_PROGRESS_CONTINUATION, replayed.single().continuation?.failureCode)
        assertEquals(INTERNAL_WORK_GOAL_PROGRESS_PROMPT, replayed.single().content)
    }
}
