package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.session.RecoveredToolResult
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAgentRunCoordinatorTest {
    @Test
    fun safeInterruptedRunQueuesStableContinuation() {
        withCoordinator { coordinator, log ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 16,
                input = "修复项目",
                memoryInput = "修复项目",
            )
            coordinator.recordEvent(context, AgentEvent.StepStarted(context.runId, 1))

            val decision = coordinator.recoveryDecision("s1", SessionRepairResult())

            assertNotNull(decision)
            assertEquals("run-recovery:" + context.runId, decision!!.queuedInput?.id)
            assertTrue(decision.queuedInput?.content.orEmpty().contains("继续执行"))
            assertNull(decision.blockedReason)
            assertTrue(log.latest(LOCAL_AGENT_RUN_CHECKPOINT_EVENT) != null)
        }
    }

    @Test
    fun unknownToolOutcomeBlocksAutomaticResume() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 16,
                input = "发布文件",
                memoryInput = "发布文件",
            )
            coordinator.recordEvent(
                context,
                AgentEvent.ToolStarted(
                    turnId = context.runId,
                    step = 1,
                    call = AgentToolCall(
                        id = "call-1",
                        name = "write_file",
                        arguments = buildJsonObject { },
                    ),
                ),
            )

            val repair = SessionRepairResult(
                toolResults = listOf(
                    RecoveredToolResult(
                        callId = "call-1",
                        name = "write_file",
                        step = 1,
                        code = SessionRecovery.TOOL_OUTCOME_UNKNOWN,
                        content = "unknown",
                        modelContent = "unknown",
                    ),
                ),
            )
            val decision = coordinator.recoveryDecision("s1", repair)

            assertNotNull(decision)
            assertNull(decision!!.queuedInput)
            assertTrue(decision.blockedReason.orEmpty().contains("副作用"))
        }
    }

    @Test
    fun childRunCheckpointCannotTriggerForegroundRecovery() {
        withCoordinator { coordinator, log ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 8,
                input = "检查子任务",
                memoryInput = "检查子任务",
                kind = LocalAgentRunKind.SUBAGENT,
                allowMutation = false,
            )
            coordinator.recordEvent(context, AgentEvent.StepStarted(context.runId, 1))

            assertNotNull(log.latest(LOCAL_SUBAGENT_RUN_CHECKPOINT_EVENT))
            assertNull(log.latest(LOCAL_AGENT_RUN_CHECKPOINT_EVENT))
            assertNull(coordinator.recoveryDecision("s1", SessionRepairResult()))
        }
    }

    @Test
    fun automationRunUsesIndependentCheckpointStream() {
        withCoordinator { coordinator, log ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = true,
                maxSteps = 8,
                input = "后台任务",
                memoryInput = "后台任务",
                kind = LocalAgentRunKind.AUTOMATION,
            )
            coordinator.recordEvent(
                context,
                AgentEvent.TurnCompleted(context.runId, 1, "完成"),
            )

            assertNotNull(log.latest(LOCAL_AUTOMATION_RUN_CHECKPOINT_EVENT))
            assertNull(log.latest(LOCAL_AGENT_RUN_CHECKPOINT_EVENT))
        }
    }

    @Test
    fun durableTurnEndWinsOverStaleRunningCheckpoint() {
        withCoordinator { coordinator, log ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 16,
                input = "完成任务",
                memoryInput = "完成任务",
            )
            log.append(
                "turn/end",
                kotlinx.serialization.json.buildJsonObject {
                    kotlinx.serialization.json.put("reason", "completed")
                },
            )

            assertNull(coordinator.recoveryDecision("s1", SessionRepairResult()))
        }
    }

    @Test
    fun terminalRunNeverProducesRecoveryDecision() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.CHAT,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.CHAT),
                safeAutoApprovalEnabled = false,
                maxSteps = 1,
                input = "你好",
                memoryInput = "你好",
            )
            coordinator.recordEvent(
                context,
                AgentEvent.TurnCompleted(
                    turnId = context.runId,
                    steps = 1,
                    answer = "你好",
                ),
            )

            assertNull(coordinator.recoveryDecision("s1", SessionRepairResult()))
        }
    }

    private fun withCoordinator(
        block: (LocalAgentRunCoordinator, LocalSessionEventLog) -> Unit,
    ) {
        val root = createTempDir(prefix = "agent-run-coordinator-")
        try {
            val log = LocalSessionEventLog(
                file = File(root, "s1.events.jsonl"),
                json = Json { ignoreUnknownKeys = true },
            )
            var now = 100L
            val coordinator = LocalAgentRunCoordinator(
                eventLogFor = { log },
                now = { now++ },
                idFactory = { "run-1" },
            )
            block(coordinator, log)
        } finally {
            root.deleteRecursively()
        }
    }
}
