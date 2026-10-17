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
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LocalWorkGoalContinuationTest {
    @get:Rule val files = TemporaryFolder()

    // These synthetic logs start at sequence 0, while the fixture Goal starts at sequence 12.
    private val goalFromStartOfTestLog: LocalGoal get() = goal.copy(startedAtSequence = -1L)

    private fun toolLog(): LocalSessionEventLog = LocalSessionEventLog(
        file = File(files.root, "work-${System.nanoTime()}.events.jsonl"),
        json = Json { ignoreUnknownKeys = true },
        sessionId = "goal-tool-test",
    )

    private fun LocalSessionEventLog.goalToolCall(id: String, belongsTo: String = goal.id) {
        append("tool/call", buildJsonObject {
            put("id", id)
            put("name", "write_file")
            put("goal_id", belongsTo)
            // This fixture models the modern durable declaration, before executor admission.
            put("execution_started", false)
        })
    }

    private fun LocalSessionEventLog.goalToolStarted(id: String) {
        append("tool/execution-started", buildJsonObject {
            put("id", id)
            put("execution_id", "exec-$id")
            put("attributes", buildJsonObject { put("goal_id", goal.id) })
        })
    }

    private fun LocalSessionEventLog.goalToolResult(
        id: String,
        isError: Boolean = false,
        unknown: Boolean = false,
    ) {
        append("tool/result", buildJsonObject {
            put("id", id)
            put("execution_id", "exec-$id")
            put("is_error", isError)
            if (unknown) {
                put("error_code", "TOOL_OUTCOME_UNKNOWN")
                put("side_effect", "possible")
            }
        })
    }

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
    fun unknownOrUnsettledSideEffectBlocksAllAutomaticGoalFollowUp() {
        val log = toolLog()
        try {
            log.goalToolCall("write-1")
            log.goalToolStarted("write-1")
            assertTrue(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
            log.goalToolResult("write-1", isError = true, unknown = true)
            assertTrue(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
            // A subsequent successful tool cannot prove an earlier unknown mutation is safe.
            log.goalToolCall("read-2")
            log.goalToolStarted("read-2")
            log.goalToolResult("read-2")
            assertTrue(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
        } finally {
            log.close()
        }
    }

    @Test
    fun settledGoalToolsDoNotBlockAndUnrelatedGoalEffectsRemainIsolated() {
        val log = toolLog()
        try {
            log.goalToolCall("other-1", belongsTo = "other-goal")
            log.append("tool/execution-started", buildJsonObject {
                put("id", "other-1")
                put("attributes", buildJsonObject { put("goal_id", "other-goal") })
            })
            assertFalse(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
            log.goalToolCall("write-ok")
            log.goalToolStarted("write-ok")
            log.goalToolResult("write-ok")
            assertFalse(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
        } finally {
            log.close()
        }
    }

    @Test
    fun toolDeclaredButNeverStartedHasNoUnknownExternalSideEffect() {
        val log = toolLog()
        try {
            log.goalToolCall("unstarted")
            assertFalse(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
        } finally {
            log.close()
        }
    }

    @Test
    fun oldUnstampedToolCallStillBlocksAutomaticReplayUntilResult() {
        val log = toolLog()
        try {
            log.append("tool/call", buildJsonObject {
                put("id", "legacy-side-effect")
                put("goal_id", goal.id)
                put("name", "write_file")
                // Historical records lacked execution_started. Outcome stays unknown.
            })
            assertTrue(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
            log.goalToolResult("legacy-side-effect")
            assertFalse(hasUnresolvedWorkGoalToolEffects(log, goalFromStartOfTestLog))
        } finally { log.close() }
    }

    @Test
    fun taskGraphWithoutTerminalEventBlocksAutomaticGoalReplayAfterRecovery() {
        val log = toolLog()
        try {
            log.append("taskgraph/start", buildJsonObject {
                put("graph_id", "graph-1")
                put("goal_id", goal.id)
                put("goal_fingerprint", localWorkGoalFingerprint(goal))
            })
            assertTrue(hasUnsettledWorkGoalTaskGraphs(log, goalFromStartOfTestLog))
            // Progress is informative but never a settlement receipt.
            log.append("taskgraph/progress", buildJsonObject {
                put("graph_id", "graph-1")
                put("goal_id", goal.id)
                put("stage", "已完成")
            })
            assertTrue(hasUnsettledWorkGoalTaskGraphs(log, goalFromStartOfTestLog))
            log.append("taskgraph/end", buildJsonObject {
                put("graph_id", "graph-1")
                put("goal_id", goal.id)
                put("outcome", "partial")
            })
            assertFalse(hasUnsettledWorkGoalTaskGraphs(log, goalFromStartOfTestLog))
        } finally { log.close() }
    }

    @Test
    fun graphRecoveryCannotTreatAnotherGoalOrWrongTerminalAsSettlement() {
        val ledger = LocalWorkTaskGraphRecoveryLedger()
        fun event(id: String, g: String) = buildJsonObject {
            put("graph_id", id)
            put("goal_id", g)
        }
        ledger.observe("taskgraph/start", event("one", "goal-1"))
        ledger.observe("taskgraph/start", event("two", "goal-2"))
        ledger.observe("taskgraph/end", event("one", "goal-2"))
        assertEquals(1, ledger.unresolvedForGoal("goal-1").size)
        assertEquals(1, ledger.unresolvedForGoal("goal-2").size)
        ledger.observe("taskgraph/end", event("one", "goal-1"))
        assertTrue(ledger.unresolvedForGoal("goal-1").isEmpty())
        assertEquals(1, ledger.unresolvedForGoal("goal-2").size)
    }

    @Test
    fun recoveredGraphTerminalStatusesAllSettleExecutionWithoutClaimingGoalSuccess() {
        for (terminal in listOf("nodes_completed", "partial", "failed", "cancelled")) {
            val ledger = LocalWorkTaskGraphRecoveryLedger()
            ledger.observe("taskgraph/start", buildJsonObject {
                put("graph_id", terminal)
                put("goal_id", goal.id)
            })
            ledger.observe("taskgraph/end", buildJsonObject {
                put("graph_id", terminal)
                put("goal_id", goal.id)
                put("outcome", terminal)
            })
            assertTrue(ledger.unresolvedForGoal(goal.id).isEmpty())
        }
    }

    @Test
    fun realToolProgressWithOpenGoalCanScheduleOneMoreTurn() {
        assertTrue(canAutomaticallyFollowUpWorkGoal(conditions()))
    }

    @Test
    fun noToolProgressOrUncertainToolFailureNeverTriggersBlindReplay() {
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(hadSuccessfulTool = false)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(hadToolFailure = true)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(runtimeHealthy = false)))
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
    fun sustainedEvidenceProgressMayContinueButRepeatedStagnationStops() {
        assertTrue(canAutomaticallyFollowUpWorkGoal(conditions().copy(completedContinuations = 2)))
        assertTrue(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            completedContinuations = 12, stalledRounds = 1)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            completedContinuations = 13, stalledRounds = 2)))
        assertFalse(canAutomaticallyFollowUpWorkGoal(conditions().copy(
            completedContinuations = MAX_GOAL_CONTINUATION_ROUNDS)))
        assertEquals(0, nextGoalStalledRounds(null, 10, "proof-a"))
        assertEquals(1, nextGoalStalledRounds("proof-a", 0, "proof-a"))
        assertEquals(2, nextGoalStalledRounds("proof-a", 1, "proof-a"))
        assertEquals(0, nextGoalStalledRounds("proof-a", 2, "proof-b"))
    }

    @Test
    fun realArtifactVersionChangesTheGoalProgressProof() {
        val root = files.newFolder("project-progress")
        val work = LocalWorkState(goal = goal, todos = listOf(LocalTodoItem("修复后验证", "pending")))
        val evidence = localWorkCompletionEvidence(work)
        val target = java.io.File(root, "src/Feature.kt")
        target.parentFile.mkdirs()
        target.writeText("first")
        val before = workGoalProgressFingerprint(work, evidence, root)
        assertEquals(before, workGoalProgressFingerprint(work, evidence, root))
        target.writeText("second")
        val after = workGoalProgressFingerprint(work, evidence, root)
        assertFalse(before == after)
        val finished = work.copy(todos = listOf(LocalTodoItem("修复后验证", "completed")))
        assertFalse(workGoalProgressFingerprint(finished,
            localWorkCompletionEvidence(finished), root) == after)
    }

    private fun pinnedGoalCommand(goal: LocalGoal = this.goal): LocalWorkResumeCommand =
        LocalWorkResumeCommand(
            sourceRunId = "run-restore",
            checkpointSequence = 88L,
            failureCode = WORK_GOAL_PROGRESS_CONTINUATION,
            index = 1,
            sourceMessageId = "original",
            prompt = INTERNAL_WORK_GOAL_PROGRESS_PROMPT,
            goalId = goal.id,
            goalFingerprint = localWorkGoalFingerprint(goal),
        )

    @Test
    fun durableGoalIdentitySurvivesQueueReplayAndDetectsChangedContract() {
        val input = pinnedGoalCommand().toQueueInput()
        val encoded = encodeLocalAgentInboxEvent("work-goal-progress-queued", listOf(input))
        val restored = requireNotNull(decodeLocalAgentInboxPending(encoded)).single()
        assertEquals(goal.id, restored.continuation?.goalId)
        assertEquals(localWorkGoalFingerprint(goal), restored.continuation?.goalFingerprint)
        assertTrue(isCurrentWorkGoalContinuation(restored.continuation, LocalWorkState(goal = goal)))
        assertFalse(isCurrentWorkGoalContinuation(restored.continuation,
            LocalWorkState(goal = goal.copy(note = "用户刚修改了目标"))))
        assertFalse(isCurrentWorkGoalContinuation(restored.continuation,
            LocalWorkState(goal = goal.copy(verificationCommands = listOf("different-check")))))
        assertFalse(isCurrentWorkGoalContinuation(restored.continuation,
            LocalWorkState(goal = goal.copy(id = "different-goal"))))
        assertFalse(isCurrentWorkGoalContinuation(restored.continuation,
            LocalWorkState(goal = goal.copy(status = "paused"))))
        assertFalse(isCurrentWorkGoalContinuation(restored.continuation, LocalWorkState()))
    }

    @Test
    fun newUserInputAndStaleCheckpointAreDiscardedBeforeModelHistoryCommit() {
        val auto = pinnedGoalCommand().toQueueInput()
        val user = com.labteto.dshmobile.harness.agent.QueuedAgentInput(
            "按我最新要求执行", id = "user-2")
        val work = LocalWorkState(goal = goal)
        assertFalse(shouldDiscardWorkGoalContinuation(auto, emptyList(), work))
        assertTrue(shouldDiscardWorkGoalContinuation(auto, listOf(user), work))
        assertTrue(shouldDiscardWorkGoalContinuation(auto, emptyList(),
            work.copy(goal = goal.copy(description = "新的执行目标"))))
        // Internal provider-failure continuation retains the previous compatible contract.
        val failure = LocalWorkResumeCommand(
            "run-restore", 88L, "MODEL_TIMEOUT", 1, "original").toQueueInput()
        assertFalse(shouldDiscardWorkGoalContinuation(failure, emptyList(), work))
        assertFalse(shouldDiscardWorkGoalContinuation(user, emptyList(), work))
    }

    @Test
    fun goalResumeNeverRestartsWhenApprovalOrTeamIsStillOutstanding() {
        val auto = pinnedGoalCommand().toQueueInput()
        val work = LocalWorkState(goal = goal)
        val approval = com.labteto.dshmobile.local.interaction.LocalApproval(
            callId = "wait", toolName = "bash", summary = "需要授权",
            arguments = "{}", access = "write")
        assertFalse(isCurrentWorkGoalContinuation(auto.continuation,
            work.copy(pendingApproval = approval)))
        assertFalse(isCurrentWorkGoalContinuation(auto.continuation,
            work.copy(team = LocalAgentTeamUiState(rebuilding = true))))
        assertFalse(isCurrentWorkGoalContinuation(auto.continuation,
            work.copy(team = LocalAgentTeamUiState(pendingMessageCount = 2))))
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
