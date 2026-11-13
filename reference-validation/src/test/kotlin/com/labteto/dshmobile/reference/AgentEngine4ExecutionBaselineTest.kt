package com.labteto.dshmobile.reference

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.session.SessionEventLog
import com.labteto.dshmobile.harness.session.ToolAttemptLedger
import com.labteto.dshmobile.harness.workflow.TaskGraphExecutor
import com.labteto.dshmobile.harness.workflow.TaskGraphNode
import com.labteto.dshmobile.harness.workflow.TaskGraphOutcome
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AE4V3-03 executable cross-capability baseline, not an upstream golden fixture.
 * Completing a model Turn or settling a single Tool cannot deliver an incomplete Goal.
 */
class AgentEngine4ExecutionBaselineTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun completedTurnAndSettledToolCannotInventCompletedGoalWhenGraphHasUnfinishedNodes() = runTest {
        val root = Files.createTempDirectory("ae4-execution-baseline").toFile()
        val file = root.resolve("session.events.jsonl")
        val log = SessionEventLog(file, json)
        try {
            var modelRequests = 0
            var toolExecutions = 0
            val call = AgentToolCall("read-1", "read_file", buildJsonObject { put("path", "report.md") })
            val engine = AgentTurnEngine(
                model = AgentModel { history ->
                    modelRequests++
                    if (modelRequests == 1) AgentModelReply(toolCalls = listOf(call))
                    else {
                        assertEquals("file-sha-123", history.last().content)
                        AgentModelReply(content = "模型声称：已经完成全部任务")
                    }
                },
                tools = AgentToolExecutor { toolCall ->
                    toolExecutions++
                    log.append("tool/execution-started", buildJsonObject {
                        put("id", toolCall.id)
                        put("name", toolCall.name)
                        put("execution_id", "exec-1")
                        put("attributes", buildJsonObject { put("goal_id", "goal-1") })
                    })
                    AgentToolResult("file-sha-123", executionId = "exec-1")
                },
                eventSink = AgentEventSink { event ->
                    when (event) {
                        is AgentEvent.ToolStarted -> log.append("tool/call", buildJsonObject {
                            put("id", event.call.id); put("name", event.call.name)
                            put("goal_id", "goal-1"); put("execution_started", false)
                        })
                        is AgentEvent.ToolFinished -> log.append("tool/result", buildJsonObject {
                            put("id", event.call.id)
                            put("execution_id", event.executionId ?: "")
                            put("is_error", event.isError)
                            put("side_effect", event.sideEffect.name.lowercase())
                        })
                        else -> log.append("agent/event", buildJsonObject {
                            put("turn_id", event.turnId)
                            put("kind", event.javaClass.simpleName)
                        })
                    }
                },
                idFactory = { "turn-1" },
            )
            val turn = engine.run("交付并验证报告")
            assertEquals(AgentStopReason.COMPLETED, turn.stopReason)
            assertEquals(2, turn.steps)
            assertEquals(1, toolExecutions)

            val attempts = ToolAttemptLedger()
            log.snapshot().forEach { attempts.observe(it) }
            assertFalse(attempts.hasUnresolvedExternalEffects("goal-1"))
            assertEquals(1, attempts.snapshot().size)
            assertTrue(attempts.snapshot().single().settled)

            val executed = mutableListOf<String>()
            val results = TaskGraphExecutor(maxNodes = 4, maxParallelism = 2).run(
                nodes = listOf(
                    TaskGraphNode("verify", "检查实际文件"),
                    TaskGraphNode("deliver", "交付", listOf("verify")),
                    TaskGraphNode("diagnose", "独立诊断"),
                ),
                onProgress = { progress ->
                    log.append("taskgraph/progress", buildJsonObject {
                        put("node_id", progress.nodeId); put("stage", progress.stage)
                    })
                },
            ) { context, _ ->
                executed += context.node.id
                if (context.node.id == "verify") "" else "diagnostic-output"
            }
            assertEquals(listOf(TaskGraphOutcome.FAILED, TaskGraphOutcome.BLOCKED,
                TaskGraphOutcome.COMPLETED), results.map { it.outcome })
            assertFalse("A blocked dependent must never execute", "deliver" in executed)
            assertEquals(3, results.size)

            log.append("goal/assessment", buildJsonObject {
                put("goal_id", "goal-1")
                put("disposition", "needs_execution")
                put("outstanding", 2)
            })
        } finally {
            log.close()
        }
        val reopened = SessionEventLog(file, json)
        try {
            val facts = reopened.snapshot()
            assertTrue(facts.isNotEmpty())
            assertEquals(facts.indices.map { it.toLong() }, facts.map { it.sequence })
            assertEquals(1, facts.count {
                it.type == "agent/event" && it.data["kind"].toString().contains("TurnCompleted")
            })
            assertEquals(1, facts.count { it.type == "goal/assessment" })
            assertEquals(0, facts.count { it.type == "goal/completed" })
            assertTrue(facts.any { it.type == "taskgraph/progress" })
        } finally {
            reopened.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun laterSettledToolCannotEraseEarlierUnknownSideEffect() {
        val ledger = ToolAttemptLedger()
        ledger.observe("tool/call", buildJsonObject {
            put("id", "write-1"); put("name", "write_file")
            put("goal_id", "goal-2"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "write-1"); put("execution_id", "exec-unknown")
        })
        ledger.observe("tool/call", buildJsonObject {
            put("id", "read-2"); put("name", "read_file")
            put("goal_id", "goal-2"); put("execution_started", false)
        })
        ledger.observe("tool/execution-started", buildJsonObject {
            put("id", "read-2"); put("execution_id", "exec-good")
        })
        ledger.observe("tool/result", buildJsonObject {
            put("id", "read-2"); put("execution_id", "exec-good")
            put("is_error", false); put("side_effect", "none")
        })
        assertTrue(ledger.hasUnresolvedExternalEffects("goal-2"))
        assertEquals(2, ledger.snapshot().size)
        assertFalse(ledger.snapshot().first().settled)
        assertTrue(ledger.snapshot().last().settled)
    }
}
