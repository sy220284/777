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
        assertEquals(
            listOf(
                AgentEvent.TurnStarted::class,
                AgentEvent.AssistantObserved::class,
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
            tools = AgentToolExecutor { "文件内容" },
            eventSink = AgentEventSink { events += it },
            idFactory = { "turn-2" },
        )

        val result = loop.run("读取文件")

        assertEquals(2, result.steps)
        assertEquals("已读取", result.answer)
        assertEquals(1, events.count { it is AgentEvent.ToolStarted })
        assertEquals(1, events.count { it is AgentEvent.ToolFinished })
        assertTrue(events.last() is AgentEvent.TurnCompleted)
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
            tools = AgentToolExecutor { "" },
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
