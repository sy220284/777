package com.labteto.dshmobile.harness.agent

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
class AgentLoopTest {
    @Test
    fun completesPlainTurnWithDurableOrdering() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
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
        val loop = AgentLoop(
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
        val loop = AgentLoop(
            model = AgentModel { messages ->
                request += 1
                if (request == 1) {
                    AgentModelReply(toolCalls = listOf(first, second))
                } else {
                    assertEquals(listOf(AgentToolResult("结果甲"), AgentToolResult("结果乙")), messages.takeLast(2).map { it.content })
                    AgentModelReply(content = "汇总")
                }
            },
            tools = AgentToolExecutor { error("批量执行器存在时不应走单调用执行器") },
            toolBatch = AgentToolBatchExecutor { calls ->
                observedCalls = calls
                listOf(AgentToolResult("结果甲"), AgentToolResult("结果乙"))
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
    }

    @Test
    fun structuredToolErrorIsPreservedButStillAllowsModelRecovery() = runTest {
        val events = mutableListOf<AgentEvent>()
        var request = 0
        val loop = AgentLoop(
            model = AgentModel { messages ->
                request += 1
                if (request == 1) {
                    AgentModelReply(
                        toolCalls = listOf(
                            AgentToolCall("call-error", "read", buildJsonObject { put("path", "missing") }),
                        ),
                    )
                } else {
                    assertEquals("读取失败", messages.last().content)
                    AgentModelReply(content = "已改用其他方案")
                }
            },
            tools = AgentToolExecutor { AgentToolResult("读取失败", isError = true) },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-tool-error" },
        )

        val result = loop.run("读取文件")

        val toolEvent = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(toolEvent.isError)
        assertEquals("读取失败", toolEvent.output)
        assertEquals("已改用其他方案", result.answer)
        assertTrue(events.last() is AgentEvent.TurnCompleted)
    }

    @Test
    fun stepLimitIsAControlledStopInsteadOfFailure() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
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
    fun modelFailureProducesFailureWithoutCompletion() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
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
    fun toolFailureProducesFailureWithoutSyntheticToolResult() = runTest {
        val events = mutableListOf<AgentEvent>()
        val loop = AgentLoop(
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
        val loop = AgentLoop(
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
}
