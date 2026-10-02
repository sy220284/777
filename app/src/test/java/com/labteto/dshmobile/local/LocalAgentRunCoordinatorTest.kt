package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.agent.AgentEvent
import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolSideEffect
import com.labteto.dshmobile.harness.session.ModelHistoryCheckpointCodec
import com.labteto.dshmobile.harness.session.RecoveredToolResult
import com.labteto.dshmobile.harness.session.SessionRepairResult
import com.labteto.dshmobile.harness.session.SessionRecovery
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun safeRecoveryIncludesLatestTypedWorkCheckpoint() {
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
                input = "继续项目",
                memoryInput = "继续项目",
            )
            coordinator.recordEvent(context, AgentEvent.StepStarted(context.runId, 1))
            val checkpoint = LocalWorkCheckpoint(
                goals = listOf("完成恢复链"),
                constraints = listOf("禁止盲目重放副作用"),
                decisions = listOf("Session Event 是事实源"),
                failures = listOf("旧方案重复写入"),
                unfinished = listOf("补 Android 17 回归"),
                progress = listOf("基础恢复已完成"),
                artifacts = listOf("docs/PROTOCOL.md"),
                tools = listOf("write_file"),
            )
            log.append(
                ModelHistoryCheckpointCodec.EVENT_TYPE,
                ModelHistoryCheckpointCodec().encode(
                    messages = listOf(
                        buildJsonObject {
                            put("role", "system")
                            put("content", "系统")
                        },
                        buildTrustedWorkCheckpointModelMessage(checkpoint.toModelBlock()),
                    ),
                    reason = "test",
                ),
            )

            val decision = requireNotNull(
                coordinator.recoveryDecision("s1", SessionRepairResult()),
            )

            val content = decision.queuedInput?.content.orEmpty()
            assertTrue(content.contains("<work-checkpoint>"))
            assertTrue(content.contains("补 Android 17 回归"))
            assertTrue(content.contains("不要重做已完成步骤"))
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
    fun completedAutomationRunReturnsDurableOutputInsteadOfReplaying() {
        withCoordinator { coordinator, _ ->
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
                AgentEvent.TurnCompleted(context.runId, 2, "已经完成的后台结果"),
            )

            val decision = coordinator.recoveryDecision(
                sessionId = "s1",
                repair = SessionRepairResult(),
                kind = LocalAgentRunKind.AUTOMATION,
            )

            assertNotNull(decision)
            assertEquals("已经完成的后台结果", decision!!.completedOutput)
            assertNull(decision.queuedInput)
            assertNull(decision.blockedReason)
        }
    }

    @Test
    fun automationWithPossibleToolSideEffectBlocksReplay() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = true,
                maxSteps = 8,
                input = "修改文件",
                memoryInput = "修改文件",
                kind = LocalAgentRunKind.AUTOMATION,
                allowMutation = true,
            )
            val call = AgentToolCall(
                id = "call-write",
                name = "write",
                arguments = buildJsonObject { put("path", "a.txt") },
            )
            coordinator.recordEvent(context, AgentEvent.ToolStarted(context.runId, 1, call))
            coordinator.recordEvent(
                context,
                AgentEvent.ToolFinished(
                    turnId = context.runId,
                    step = 1,
                    call = call,
                    output = "ok",
                    sideEffect = AgentToolSideEffect.POSSIBLE,
                ),
            )

            val decision = coordinator.recoveryDecision(
                sessionId = "s1",
                repair = SessionRepairResult(),
                kind = LocalAgentRunKind.AUTOMATION,
            )

            assertNotNull(decision)
            assertNull(decision!!.queuedInput)
            assertTrue(decision.blockedReason.orEmpty().contains("重复操作"))
        }
    }

    @Test
    fun readonlySubagentCanReplayAfterInterruptedTool() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 8,
                input = "读取资料",
                memoryInput = "读取资料",
                kind = LocalAgentRunKind.SUBAGENT,
                allowMutation = false,
            )
            coordinator.recordEvent(
                context,
                AgentEvent.ToolStarted(
                    turnId = context.runId,
                    step = 1,
                    call = AgentToolCall(
                        id = "call-read",
                        name = "read",
                        arguments = buildJsonObject { put("path", "README.md") },
                    ),
                ),
            )

            val decision = coordinator.recoveryDecision(
                sessionId = "s1",
                repair = SessionRepairResult(),
                kind = LocalAgentRunKind.SUBAGENT,
            )

            assertNotNull(decision)
            assertNotNull(decision!!.queuedInput)
            assertNull(decision.blockedReason)
        }
    }

    @Test
    fun queuedRecoveryCheckpointSurvivesRestart() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 8,
                input = "继续任务",
                memoryInput = "原始任务",
            )
            coordinator.markRecoveryQueued("s1", context.runId)

            val decision = coordinator.recoveryDecision("s1", SessionRepairResult())

            assertNotNull(decision)
            assertEquals("run-recovery:" + context.runId, decision!!.queuedInput?.id)
            assertNull(decision.blockedReason)
        }
    }

    @Test
    fun blockedRecoveryCheckpointPreservesReason() {
        withCoordinator { coordinator, _ ->
            val context = coordinator.start(
                sessionId = "s1",
                usageMode = LocalUsageMode.WORK,
                model = "deepseek-flash",
                baseUrl = "https://api.deepseek.com",
                planMode = false,
                policy = localAgentRunPolicy(LocalUsageMode.WORK),
                safeAutoApprovalEnabled = false,
                maxSteps = 8,
                input = "修改外部状态",
                memoryInput = "修改外部状态",
            )
            coordinator.markRecoveryBlocked("s1", context.runId, "需要人工确认")

            val decision = coordinator.recoveryDecision("s1", SessionRepairResult())

            assertNotNull(decision)
            assertEquals("需要人工确认", decision!!.blockedReason)
            assertNull(decision.queuedInput)
        }
    }

    @Test
    fun legacyCheckpointWithoutRouteIdentityNeverAutoResumes() {
        withCoordinator { coordinator, log ->
            log.append(
                LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
                buildJsonObject {
                    put("version", 1)
                    put("status", "running")
                    put("run_id", "legacy-run")
                    put("model", "deepseek-flash")
                    put("base_url", "https://api.deepseek.com")
                    put("input", "继续旧任务")
                    put("memory_input", "继续旧任务")
                },
            )

            val decision = requireNotNull(
                coordinator.recoveryDecision("s1", SessionRepairResult()),
            )

            assertNull(decision.queuedInput)
            assertTrue(decision.blockedReason.orEmpty().contains("旧版检查点"))
            assertTrue(decision.blockedReason.orEmpty().contains("模型路由身份"))
        }
    }

    @Test
    fun incompatibleCheckpointVersionIsIgnored() {
        withCoordinator { coordinator, log ->
            log.append(
                LOCAL_AGENT_RUN_CHECKPOINT_EVENT,
                buildJsonObject {
                    put("version", 999)
                    put("status", "running")
                    put("run_id", "future-run")
                },
            )

            assertNull(coordinator.recoveryDecision("s1", SessionRepairResult()))
        }
    }

    @Test
    fun failedCancelledAndStepLimitRunsNeverReplay() {
        val terminalEvents: List<(String) -> AgentEvent> = listOf(
            { runId -> AgentEvent.TurnFailed(runId, "boom") },
            { runId -> AgentEvent.TurnCancelled(runId) },
            { runId -> AgentEvent.TurnStepLimit(runId, 8) },
        )
        terminalEvents.forEach { terminal ->
            withCoordinator { coordinator, _ ->
                val context = coordinator.start(
                    sessionId = "s1",
                    usageMode = LocalUsageMode.WORK,
                    model = "deepseek-flash",
                    baseUrl = "https://api.deepseek.com",
                    planMode = false,
                    policy = localAgentRunPolicy(LocalUsageMode.WORK),
                    safeAutoApprovalEnabled = false,
                    maxSteps = 8,
                    input = "任务",
                    memoryInput = "任务",
                )
                coordinator.recordEvent(context, terminal(context.runId))

                assertNull(coordinator.recoveryDecision("s1", SessionRepairResult()))
            }
        }
    }

    @Test
    fun durableFinalAssistantEventWinsEvenBeforeAssistantCheckpoint() {
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
                input = "给我最终结论",
                memoryInput = "给我最终结论",
            )
            log.append("turn/start", buildJsonObject { put("turn_id", context.runId) })
            log.append("step/start", buildJsonObject { put("step", 1) })
            coordinator.recordEvent(context, AgentEvent.StepStarted(context.runId, 1))
            log.append(
                "assistant/message",
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "最终结论")
                },
            )

            val repair = log.repairInterruptedTail()

            assertTrue(repair.repaired)
            assertNull(coordinator.recoveryDecision("s1", repair))
        }
    }

    @Test
    fun durableFinalAssistantReplyIsNotGeneratedTwiceAfterRestart() {
        withCoordinator { coordinator, log ->
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
            log.append("turn/start", buildJsonObject { put("turn_id", context.runId) })
            log.append("step/start", buildJsonObject { put("step", 1) })
            log.append(
                "assistant/message",
                buildJsonObject {
                    put("step", 1)
                    put("content", "已经生成的最终回复")
                },
            )
            coordinator.recordEvent(
                context,
                AgentEvent.AssistantObserved(
                    turnId = context.runId,
                    step = 1,
                    content = "已经生成的最终回复",
                    toolCalls = emptyList(),
                ),
            )

            val repair = log.repairInterruptedTail()

            assertTrue(repair.repaired)
            assertNull(coordinator.recoveryDecision("s1", repair))
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
                buildJsonObject {
                    put("reason", "completed")
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
