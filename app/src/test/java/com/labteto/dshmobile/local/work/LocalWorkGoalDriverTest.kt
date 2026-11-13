package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalUsageMode
import com.labteto.dshmobile.local.runtime.LocalAgentRunHandle
import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWorkGoalDriverTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun verifiedGoalSettlesThroughTheExistingWorkCommandAndReplaysCompleted() {
        withVerifiedGoal { binding, goal, root ->
            var persisted = 0
            val commands = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root) { persisted++ }
            val outcome = drive(binding, goal, root, commands::completeVerifiedGoal)
            assertEquals(LocalWorkGoalDriveResult.COMPLETED, outcome)
            assertEquals("completed", binding.workState.snapshot().goal?.status)
            assertEquals(goal.revision + 1L, binding.workState.snapshot().goal?.revision)
            assertEquals(1, persisted)
            val restored = projectWorkSessionControls(LocalWorkState(), binding.eventLog.snapshot())
            assertEquals(binding.workState.snapshot().goal, restored.goal)
            assertEquals("completed", binding.eventLog.latest("goal/driver")?.data?.get("result")
                ?.toString()?.trim('"'))
        }
    }

    @Test
    fun sameIntegrationGateProtectsManualAndAutomaticGoalCompletion() {
        withVerifiedGoal { binding, goal, root ->
            fun updateReviewer(status: String) {
                binding.workState.update { work ->
                    work.copy(team = LocalAgentTeamUiState(tasks = listOf(
                        LocalAgentTeamTaskUiState(
                            id = "task-1", subject = "独立复核", description = "",
                            status = "completed", revision = 3,
                            completionResultId = "worker-proof",
                            completionEvidenceSequence = 25L,
                            independentReviewStatus = status,
                        ),
                    )))
                }
            }
            val commands = LocalWorkProgressCoordinator(
                binding.workState, binding.eventLog, root,
            ) { }
            updateReviewer("review_invalid")
            assertThrows(IllegalStateException::class.java) { commands.updateGoal("completed", null) }
            assertEquals("active", binding.workState.snapshot().goal?.status)
            assertFalse(decideLocalWorkIntegration(localWorkCompletionEvidence(
                binding.workState.snapshot(),
                localWorkDeliveryEvidence(binding.eventLog, goal, root),
            )).readyForGoalAdmission)
            updateReviewer("reviewer_rejected")
            assertThrows(IllegalStateException::class.java) { commands.updateGoal("completed", null) }
            assertEquals("active", binding.workState.snapshot().goal?.status)
            updateReviewer("reviewer_approved")
            assertEquals(LocalWorkGoalDriveResult.COMPLETED,
                drive(binding, goal, root, commands::completeVerifiedGoal))
            assertEquals("completed", binding.workState.snapshot().goal?.status)
            val diagnostic = binding.eventLog.latest("goal/driver")?.data
            assertEquals("\"ready_for_goal_admission\"",
                diagnostic?.get("integration_disposition").toString())
        }
    }

    @Test
    fun inFlightGoalUpdateExcludesOnlyItsOwnExactInvocation() {
        withVerifiedGoal { binding, goal, root ->
            fun start(id: String, name: String) {
                binding.eventLog.append("tool/call", buildJsonObject {
                    put("id", id); put("name", name); put("goal_id", goal.id); put("execution_started", false)
                })
                binding.eventLog.append("tool/execution-started", buildJsonObject {
                    put("id", id); put("execution_id", id)
                    put("attributes", buildJsonObject { put("goal_id", goal.id) })
                })
            }
            start("current-update", "update_goal")
            val commands = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root,
                executingToolId = "current-update") { }
            start("old-write", "write")
            assertThrows(IllegalStateException::class.java) { commands.updateGoal("completed", null) }
            assertEquals("active", binding.workState.snapshot().goal?.status)
            binding.eventLog.append("tool/result", buildJsonObject {
                put("id", "old-write"); put("execution_id", "old-write"); put("is_error", false)
            })
            commands.updateGoal("completed", null)
            assertEquals("completed", binding.workState.snapshot().goal?.status)
        }
    }

    @Test
    fun automaticDriverPreservesUnknownEffectsAndNeverCompletesAnOpenEndedGoal() {
        withVerifiedGoal { binding, goal, root ->
            binding.eventLog.append("tool/call", buildJsonObject {
                put("id", "unknown"); put("goal_id", goal.id); put("execution_started", false)
            })
            binding.eventLog.append("tool/execution-started", buildJsonObject {
                put("id", "unknown"); put("execution_id", "unknown-instance")
            })
            assertEquals(LocalWorkGoalDriveResult.PARTIAL,
                drive(binding, goal, root) { error("unknown effect cannot settle a goal") })
            assertEquals("active", binding.workState.snapshot().goal?.status)
        }
        val goal = LocalGoal("开放式任务", id = "open", revision = 1L)
        assertEquals(LocalWorkGoalDriveResult.VERIFICATION_REQUIRED,
            decideLocalWorkGoalDrive(conditions(goal), hasMachineAcceptance = false))
        assertEquals(LocalWorkGoalDriveResult.WAITING,
            decideLocalWorkGoalDrive(conditions(goal).copy(pendingInputs = 1), true))
        assertEquals(LocalWorkGoalDriveResult.STALE,
            decideLocalWorkGoalDrive(conditions(goal).copy(steeringUnchanged = false), true))
    }

    @Test
    fun aGoalCreatedInThisTurnCanDriveButAnOldOrReactivatedGoalCannotBorrowTheTurn() {
        val goal = LocalGoal("目标", id = "goal", startedAtSequence = 8L, revision = 1L)
        assertEquals(goal, localWorkGoalBaselineForTurn(null, goal, 3L))
        assertNull(localWorkGoalBaselineForTurn(null, goal, 10L))
        assertNull(localWorkGoalBaselineForTurn(null, goal.copy(revision = 3L), 3L))
    }


    @Test
    fun ownToolNoteRevisionRemainsEligibleForGoalCompletion() {
        withVerifiedGoal { binding, goal, root ->
            val start = binding.eventLog.append(
                com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
                buildJsonObject { put("run_id", "run"); put("phase", "turn_started") })
            binding.eventLog.append("tool/call", buildJsonObject {
                put("id", "note"); put("goal_id", goal.id); put("execution_started", false) })
            binding.eventLog.append("tool/execution-started", buildJsonObject {
                put("id", "note"); put("execution_id", "exec-note")
                put("attributes", buildJsonObject { put("goal_id", goal.id); put("run_id", "run") }) })
            val cmd = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root,
                executingToolId = "exec-note") { }
            cmd.updateGoal("active", "实际进展已记录")
            binding.eventLog.append("tool/result", buildJsonObject {
                put("id", "note"); put("execution_id", "exec-note"); put("is_error", false) })
            val current = requireNotNull(binding.workState.snapshot().goal)
            assertEquals(current, localWorkGoalBaselineAfterVerifiedToolUpdates(
                goal, current, "run", start.sequence, binding.eventLog))
            val finish = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root) { }
            assertEquals(LocalWorkGoalDriveResult.COMPLETED,
                drive(binding, goal, root, finish::completeVerifiedGoal))
            assertEquals("completed", binding.workState.snapshot().goal?.status)
        }
    }

    @Test
    fun changesWithoutCurrentRunToolProofNeverRebaseTheGoal() {
        withVerifiedGoal { binding, goal, root ->
            val start = binding.eventLog.append(
                com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
                buildJsonObject { put("run_id", "run"); put("phase", "turn_started") })
            val external = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root) { }
            external.updateGoal("active", "外部改动")
            val changed = requireNotNull(binding.workState.snapshot().goal)
            assertEquals(goal, localWorkGoalBaselineAfterVerifiedToolUpdates(
                goal, changed, "run", start.sequence, binding.eventLog))
            assertEquals(LocalWorkGoalDriveResult.STALE,
                drive(binding, goal, root) { error("external change must not settle") })
        }
    }

    @Test
    fun anotherRunsToolCannotAuthorizeTheCurrentGoalRevision() {
        withVerifiedGoal { binding, goal, root ->
            val start = binding.eventLog.append(
                com.labteto.dshmobile.local.runtime.LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
                buildJsonObject { put("run_id", "run"); put("phase", "turn_started") })
            binding.eventLog.append("tool/call", buildJsonObject {
                put("id", "note"); put("goal_id", goal.id); put("execution_started", false) })
            binding.eventLog.append("tool/execution-started", buildJsonObject {
                put("id", "note"); put("execution_id", "exec-note")
                put("attributes", buildJsonObject { put("goal_id", goal.id); put("run_id", "other-run") }) })
            val cmd = LocalWorkProgressCoordinator(binding.workState, binding.eventLog, root,
                executingToolId = "exec-note") { }
            cmd.updateGoal("active", "别的运行提交")
            binding.eventLog.append("tool/result", buildJsonObject {
                put("id", "note"); put("execution_id", "exec-note"); put("is_error", false) })
            val changed = requireNotNull(binding.workState.snapshot().goal)
            assertEquals(goal, localWorkGoalBaselineAfterVerifiedToolUpdates(
                goal, changed, "run", start.sequence, binding.eventLog))
            assertEquals(LocalWorkGoalDriveResult.STALE,
                drive(binding, goal, root) { error("other run cannot settle") })
        }
    }

    private fun conditions(goal: LocalGoal) = LocalWorkGoalFollowUpConditions(
        originalGoal = goal, currentGoal = goal, disposition = LocalWorkGoalDisposition.READY_FOR_SETTLEMENT,
        planMode = false, hadSuccessfulTool = true, hadToolFailure = false,
        cancelled = false, pendingInputs = 0, steeringUnchanged = true, completedContinuations = 0,
    )

    private fun drive(binding: LocalWorkRunBinding, goal: LocalGoal, root: File,
        complete: (LocalGoal) -> Boolean) = driveLocalWorkGoalAfterTurn(
        binding, goal, "run", "source", 0L, true, false, root, complete)

    private fun withVerifiedGoal(block: (LocalWorkRunBinding, LocalGoal, File) -> Unit) {
        val root = files.newFolder()
        File(root, "done.txt").writeText("actual artifact")
        val log = LocalSessionEventLog(File(root, "events.jsonl"), Json)
        val first = LocalGoal("生成文件", id = "goal", artifactPaths = listOf("done.txt"), revision = 1L)
        val created = log.append("goal/state", workGoalEvent(first))
        val goal = first.copy(startedAtSequence = created.sequence)
        log.append("tool/call", buildJsonObject {
            put("id", "write"); put("name", "write"); put("goal_id", goal.id); put("execution_started", false)
            put("arguments", buildJsonObject { put("path", "done.txt") })
        })
        log.append("tool/execution-started", buildJsonObject {
            put("id", "write"); put("name", "write"); put("execution_id", "exec-write")
            put("attributes", buildJsonObject { put("goal_id", goal.id) })
        })
        log.append("tool/result", buildJsonObject {
            put("id", "write"); put("name", "write"); put("execution_id", "exec-write"); put("is_error", false)
            put("file_versions", buildJsonObject { put("done.txt", requireNotNull(localWorkFileSha256(root, "done.txt"))) })
        })
        val binding = LocalWorkRunBinding("session",
            LocalHarnessState(sessionId = "session", usageMode = LocalUsageMode.WORK,
                work = LocalWorkState(goal = goal)).toLocalWorkRunState(),
            LocalHarnessSession(id = "session", usageMode = LocalUsageMode.WORK),
            LocalAgentRunHandle(initialSessionId = "session", maxPendingInputs = 8), log)
        try { block(binding, goal, root) } finally { log.close() }
    }
}
