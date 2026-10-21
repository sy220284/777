package com.labteto.dshmobile.harness.agent

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnEngineTest {
    @Test
    fun completesPlainTurnWithDurableOrdering() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentTurnEngine(
            model = AgentModel { AgentModelReply(content = "完成") },
            tools = AgentToolExecutor { error("不应调用工具") },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-1" },
        )

        val result = loop.run("开始")

        assertEquals("完成", result.answer)
        assertEquals(1, result.steps)
        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(
            listOf(
                AgentEvent.TurnStarted::class,
                AgentEvent.StepStarted::class,
                AgentEvent.AssistantObserved::class,
                AgentEvent.StepFinished::class,
                AgentEvent.TurnCompleted::class,
            ),
            events.map { it::class },
        )
    }

    @Test
    fun feedsToolResultIntoTheNextModelStep() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val model = AgentModel { messages ->
            request += 1
            if (request == 1) {
                AgentModelReply(
                    toolCalls = listOf(
                        AgentToolCall("call-1", "read", buildJsonObject { put("path", "README.md") }),
                    ),
                )
            } else {
                assertEquals("文件内容", messages.last().content)
                assertEquals("call-1", messages.last().toolCallId)
                AgentModelReply(content = "已读取")
            }
        }
        val loop = AgentTurnEngine(
            model = model,
            tools = AgentToolExecutor { AgentToolResult("文件内容") },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-2" },
        )

        val result = loop.run("读取文件")

        assertEquals(2, result.steps)
        assertEquals("已读取", result.answer)
        assertEquals(2, events.count { it is AgentEvent.StepStarted })
        assertEquals(2, events.count { it is AgentEvent.StepFinished })
        assertEquals(1, events.count { it is AgentEvent.ToolStarted })
        assertEquals(1, events.count { it is AgentEvent.ToolFinished })
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }

    @Test
    fun batchExecutorPreservesRawArgumentsAndModelOrder() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        var observedCalls = emptyList<AgentToolCall>()
        val first = AgentToolCall(
            id = "call-a",
            name = "subagent",
            arguments = buildJsonObject { put("task", "甲") },
            rawArguments = "{ \"task\": \"甲\" }",
        )
        val second = AgentToolCall(
            id = "call-b",
            name = "subagent",
            arguments = buildJsonObject { put("task", "乙") },
            rawArguments = "{\"task\":\"乙\"}",
        )
        val loop = AgentTurnEngine(
            model = AgentModel { messages ->
                request += 1
                if (request == 1) {
                    AgentModelReply(toolCalls = listOf(first, second))
                } else {
                    assertEquals(listOf("结果甲", "结果乙"), messages.takeLast(2).map { it.content })
                    AgentModelReply(content = "汇总")
                }
            },
            tools = AgentToolExecutor { error("批量执行器存在时不应走单调用执行器") },
            toolBatch = AgentToolBatchExecutor { calls ->
                observedCalls = calls
                listOf(AgentToolResult("结果甲", processExitCode = 0, fileVersions = mapOf("a.kt" to "sha-a"), executionId = "exec-a"),
                    AgentToolResult("结果乙", processExitCode = 0, fileVersions = mapOf("b.kt" to "sha-b"), executionId = "exec-b"))
            },
            isParallelTool = { true },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-batch" },
        )

        val result = loop.run("并行处理")

        assertEquals("汇总", result.answer)
        assertEquals("{ \"task\": \"甲\" }", observedCalls.first().rawArguments)
        assertEquals(
            listOf("call-a", "call-b"),
            events.filterIsInstance<AgentEvent.ToolFinished>().map { it.call.id },
        )
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(listOf("exec-a", "exec-b"), finished.map { it.executionId })
        assertEquals(mapOf("a.kt" to "sha-a"), finished.first().fileVersions)
        assertEquals(0, finished.last().processExitCode)
    }

    @Test
    fun toolResultRetentionPropagatesToFinishedEvent() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val loop = AgentTurnEngine(
            model = AgentModel {
                request += 1
                if (request == 1) {
                    AgentModelReply(
                        toolCalls = listOf(AgentToolCall("shot", "screenshot", buildJsonObject { })),
                    )
                } else {
                    AgentModelReply(content = "完成")
                }
            },
            tools = AgentToolExecutor {
                AgentToolResult("pixels", retention = ToolResultRetention.EPHEMERAL)
            },
            eventSink = AgentEventSink { events += it },
        )

        loop.run("截图")

        assertEquals(
            ToolResultRetention.EPHEMERAL,
            events.filterIsInstance<AgentEvent.ToolFinished>().single().retention,
        )
    }

    @Test
    fun structuredToolErrorIsPreservedButStillAllowsModelRecovery() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val loop = AgentTurnEngine(
            model = AgentModel { messages ->
                request += 1
                if (request == 1) {
                    AgentModelReply(
                        toolCalls = listOf(
                            AgentToolCall("call-error", "read", buildJsonObject { put("path", "missing") }),
                        ),
                    )
                } else {
                    val visible = messages.last().content
                    assertTrue(visible.contains("\"status\":\"error\""))
                    assertTrue(visible.contains("\"error_code\":\"READ_FAILED\""))
                    assertTrue(visible.contains("\"retryable\":true"))
                    assertTrue(visible.contains("\"side_effect\":\"none\""))
                    assertTrue(visible.contains("读取失败"))
                    AgentModelReply(content = "已改用其他方案")
                }
            },
            tools = AgentToolExecutor {
                AgentToolResult(
                    content = "读取失败",
                    isError = true,
                    errorCode = "READ_FAILED",
                    retryable = true,
                    sideEffect = AgentToolSideEffect.NONE,
                    recoveryHint = "检查路径后重试",
                    processExitCode = 7,
                    executionId = "exec-failed",
                )
            },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-tool-error" },
        )

        val result = loop.run("读取文件")

        val toolEvent = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("exec-failed", toolEvent.executionId)
        assertEquals(7, toolEvent.processExitCode)
        assertTrue(toolEvent.isError)
        assertEquals("读取失败", toolEvent.output)
        assertEquals("READ_FAILED", toolEvent.errorCode)
        assertTrue(toolEvent.retryable)
        assertEquals(AgentToolSideEffect.NONE, toolEvent.sideEffect)
        assertEquals("检查路径后重试", toolEvent.recoveryHint)
        assertEquals("已改用其他方案", result.answer)
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }

    @Test
    fun stepLimitIsAControlledStopInsteadOfFailure() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentTurnEngine(
            model = AgentModel {
                AgentModelReply(
                    toolCalls = listOf(
                        AgentToolCall("call-limit", "read", buildJsonObject { put("path", "x") }),
                    ),
                )
            },
            tools = AgentToolExecutor { AgentToolResult("继续") },
            eventSink = AgentEventSink { events += it },
            maxSteps = 1,
            idFactory = { "turn-limit" },
        )

        val result = loop.run("执行")

        assertEquals(AgentStopReason.STEP_LIMIT, result.stopReason)
        assertTrue(events.last() is AgentEvent.TurnStepLimit)
        assertFalse(events.any { it is AgentEvent.TurnFailed })
    }

    @Test
    fun softStepBudgetExtendsSameTurnUntilTaskCompletes() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val loop = AgentTurnEngine(
            model = AgentModel {
                request += 1
                if (request < 4) {
                    AgentModelReply(
                        toolCalls = listOf(
                            AgentToolCall(
                                id = "call-$request",
                                name = "work",
                                arguments = buildJsonObject { put("step", request) },
                            ),
                        ),
                    )
                } else {
                    AgentModelReply(content = "任务完成")
                }
            },
            tools = AgentToolExecutor { AgentToolResult("继续") },
            eventSink = AgentEventSink { events += it },
            maxSteps = 1,
            stepLimitExtender = AgentStepLimitExtender { currentLimit, _ -> currentLimit + 1 },
            idFactory = { "turn-soft-budget" },
        )

        val result = loop.run("完成长任务")

        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(4, result.steps)
        assertEquals("任务完成", result.answer)
        assertEquals(4, events.count { it is AgentEvent.StepStarted })
        assertFalse(events.any { it is AgentEvent.TurnStepLimit })
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }

    @Test
    fun modelFailureProducesFailureWithoutCompletion() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentTurnEngine(
            model = AgentModel { error("模型失败") },
            tools = AgentToolExecutor { AgentToolResult("") },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-model-failure" },
        )

        val result = runCatching { loop.run("开始") }

        assertTrue(result.isFailure)
        assertTrue(events.last() is AgentEvent.TurnFailed)
        assertFalse(events.any { it is AgentEvent.TurnCompleted })
        assertFalse(events.any { it is AgentEvent.StepFinished })
    }

    @Test
    fun terminalFailureEventCannotReplacePrimaryEventSinkFailure() = runTest {
        val primary = IllegalStateException("checkpoint write failed")
        val secondary = IllegalStateException("turn failed write also failed")
        val loop = AgentTurnEngine(
            model = AgentModel { AgentModelReply(content = "完成") },
            tools = AgentToolExecutor { AgentToolResult("") },
            eventSink = AgentEventSink { event ->
                when (event) {
                    is AgentEvent.AssistantObserved -> throw primary
                    is AgentEvent.TurnFailed -> throw secondary
                    else -> Unit
                }
            },
            idFactory = { "turn-primary-failure" },
        )

        val failure = runCatching { loop.run("开始") }.exceptionOrNull()

        assertTrue(failure === primary)
        assertEquals(1, failure?.suppressed?.size)
        assertTrue(failure?.suppressed?.single() === secondary)
    }

    @Test
    fun toolFailureProducesFailureWithoutSyntheticToolResult() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentTurnEngine(
            model = AgentModel {
                AgentModelReply(
                    toolCalls = listOf(
                        AgentToolCall("call-fail", "read", buildJsonObject { put("path", "x") }),
                    ),
                )
            },
            tools = AgentToolExecutor { error("工具失败") },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-tool-failure" },
        )

        val result = runCatching { loop.run("开始") }

        assertTrue(result.isFailure)
        assertEquals(1, events.count { it is AgentEvent.ToolStarted })
        assertEquals(0, events.count { it is AgentEvent.ToolFinished })
        assertTrue(events.last() is AgentEvent.TurnFailed)
        assertFalse(events.any { it is AgentEvent.TurnCompleted })
    }

    @Test
    fun cancellationNeverProducesCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentEvent>()
        val loop = AgentTurnEngine(
            model = AgentModel {
                gate.await()
                AgentModelReply(content = "不会返回")
            },
            tools = AgentToolExecutor { AgentToolResult("") },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-3" },
        )

        val running = async { loop.run("等待") }
        runCurrent()
        running.cancel()
        runCurrent()

        assertTrue(events.any { it is AgentEvent.TurnCancelled })
        assertFalse(events.any { it is AgentEvent.TurnCompleted })
    }

    @Test
    fun softBudgetCanCrossLegacy128LimitWithoutResettingTurnOrStepNumbers() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val loop = AgentTurnEngine(
            model = AgentModel {
                request += 1
                if (request <= 129) {
                    AgentModelReply(
                        toolCalls = listOf(
                            AgentToolCall(
                                id = "call-" + request,
                                name = "work",
                                arguments = buildJsonObject { put("step", request) },
                            ),
                        ),
                    )
                } else {
                    AgentModelReply(content = "跨过旧上限后完成")
                }
            },
            tools = AgentToolExecutor { AgentToolResult("继续") },
            eventSink = AgentEventSink { events += it },
            maxSteps = 128,
            stepLimitExtender = AgentStepLimitExtender { currentLimit, stepsUsed ->
                assertEquals(currentLimit, stepsUsed)
                if (currentLimit == 128) 130 else null
            },
            idFactory = { "turn-cross-128" },
        )

        val result = loop.run("执行超长任务")

        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(130, result.steps)
        assertEquals("跨过旧上限后完成", result.answer)
        assertEquals(
            (1..130).toList(),
            events.filterIsInstance<AgentEvent.StepStarted>().map { it.step },
        )
        assertEquals(1, events.map { it.turnId }.distinct().size)
        assertFalse(events.any { it is AgentEvent.TurnStepLimit })
    }

    @Test
    fun nonGrowingSoftBudgetStopsExactlyAtExhaustedLimit() = runTest {
        val events = mutableListOf<AgentEvent>()
        var extenderCalls = 0
        val loop = AgentTurnEngine(
            model = AgentModel {
                AgentModelReply(
                    toolCalls = listOf(
                        AgentToolCall(
                            id = "call-limit",
                            name = "work",
                            arguments = buildJsonObject { },
                        ),
                    ),
                )
            },
            tools = AgentToolExecutor { AgentToolResult("继续") },
            eventSink = AgentEventSink { events += it },
            maxSteps = 3,
            stepLimitExtender = AgentStepLimitExtender { currentLimit, stepsUsed ->
                extenderCalls += 1
                assertEquals(3, currentLimit)
                assertEquals(3, stepsUsed)
                currentLimit
            },
            idFactory = { "turn-soft-stop" },
        )

        val result = loop.run("执行到动态熔断")

        assertEquals(AgentStopReason.STEP_LIMIT, result.stopReason)
        assertEquals(3, result.steps)
        assertEquals(1, extenderCalls)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.StepStarted>().map { it.step })
        assertTrue(events.last() is AgentEvent.TurnStepLimit)
        assertFalse(events.any { it is AgentEvent.TurnFailed })
    }


    @Test
    fun toolDispatchMixesSequentialAndParallelGroupsWithoutReorderingSettlements() = runTest {
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val a = AgentToolCall("a", "write", buildJsonObject {})
        val b = AgentToolCall("b", "read", buildJsonObject {})
        val c = AgentToolCall("c", "read", buildJsonObject {})
        val d = AgentToolCall("d", "write", buildJsonObject {})
        val executed = mutableListOf<String>()
        val engine = AgentTurnEngine(
            model = AgentModel {
                calls++
                if (calls == 1) AgentModelReply(toolCalls = listOf(a, b, c, d))
                else AgentModelReply(content = "完毕")
            },
            tools = AgentToolExecutor { call ->
                executed += call.id
                AgentToolResult("result-${call.id}")
            },
            toolBatch = AgentToolBatchExecutor { batch ->
                executed += batch.map { it.id }
                batch.map { AgentToolResult("result-${it.id}") }
            },
            isParallelTool = { it.name == "read" },
            eventSink = AgentEventSink { events += it },
            idFactory = { "mixed" },
        )
        val result = engine.run("实施")
        assertEquals(listOf("a", "b", "c", "d"), executed)
        assertEquals(listOf("a", "b", "c", "d"),
            events.filterIsInstance<AgentEvent.ToolFinished>().map { it.call.id })
        assertEquals("完毕", result.answer)
        assertEquals(2, result.steps)
    }

    @Test
    fun invalidAssistantToolDeclarationCannotReachExecution() = runTest {
        var toolCalls = 0
        val events = mutableListOf<AgentEvent>()
        val bad = AgentToolCall("", "write", buildJsonObject {})
        val engine = AgentTurnEngine(
            model = AgentModel { AgentModelReply(toolCalls = listOf(bad)) },
            tools = AgentToolExecutor { toolCalls++; AgentToolResult("unexpected") },
            eventSink = AgentEventSink { events += it },
        )
        assertTrue(runCatching { engine.run("执行") }.isFailure)
        assertEquals(0, toolCalls)
        assertTrue(events.last() is AgentEvent.TurnFailed)
        assertFalse(events.any { it is AgentEvent.ToolStarted })
    }

    @Test
    fun configuredBudgetCanStartAboveLegacy128Limit() = runTest {
        val loop = AgentTurnEngine(
            model = AgentModel { AgentModelReply(content = "完成") },
            tools = AgentToolExecutor { error("不应调用工具") },
            maxSteps = 256,
            idFactory = { "turn-configured-over-128" },
        )

        val result = loop.run("执行")

        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(1, result.steps)
        assertEquals("完成", result.answer)
    }

    @Test
    fun partialRepliesPreserveHistoryWithoutExecutingToolsOrCompleting() = runTest {
        AgentCompletionReason.entries.filter { it != AgentCompletionReason.COMPLETED }.forEach { reason ->
            val events = mutableListOf<AgentEvent>()
            val result = AgentTurnEngine(
                model = AgentModel { AgentModelReply("第一段", listOf(AgentToolCall("c", "write", buildJsonObject {})), reason, "provider-reason") },
                tools = AgentToolExecutor { error("部分响应不能执行工具") },
                eventSink = AgentEventSink { events += it },
            ).run("开始")
            assertEquals(AgentStopReason.valueOf(reason.name), result.stopReason)
            assertEquals("第一段", result.answer)
            assertEquals(reason, result.messages.last().completionReason)
            assertTrue(result.messages.last().toolCalls.isEmpty())
            assertFalse(events.any { it is AgentEvent.TurnCompleted || it is AgentEvent.ToolStarted })
        }
    }

}
