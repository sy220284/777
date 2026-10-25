package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEagerToolStepTest {
    private val call = AgentToolCall("c1", "write", buildJsonObject { })
    @Test fun toolStartsBeforeModelCompletesAndIsNotDispatchedTwice() = runTest {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentEvent>()
        var count = 0
        val engine = AgentTurnEngine(model = AgentModel {
            val eager = requireNotNull(currentCoroutineContext()[AgentEagerToolContext])
            eager.offer(call); eager.offer(call)
            withTimeout(1000) { started.await() }
            assertEquals(1, count)
            AgentModelReply(toolCalls = listOf(call))
        }, tools = AgentToolExecutor { count++; started.complete(Unit); AgentToolResult("receipt") },
            maxSteps = 1, eventSink = AgentEventSink { events += it })
        val result = engine.run("task")
        assertEquals(AgentStopReason.STEP_LIMIT, result.stopReason)
        assertEquals(1, count)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertTrue(events.indexOfFirst { it is AgentEvent.ToolStarted } < events.indexOfFirst { it is AgentEvent.AssistantObserved })
    }
    @Test fun interruptionSettlesOnlyAlreadyAdmittedCalls() = runTest {
        val unstarted = call.copy(id = "never")
        var count = 0
        val result = AgentTurnEngine(model = AgentModel {
            requireNotNull(currentCoroutineContext()[AgentEagerToolContext]).offer(call)
            AgentModelReply(toolCalls = listOf(call, unstarted), completionReason = AgentCompletionReason.INTERRUPTED)
        }, tools = AgentToolExecutor { count++; AgentToolResult("receipt") }).run("task")
        assertEquals(1, count)
        assertEquals(AgentStopReason.INTERRUPTED, result.stopReason)
        assertEquals(listOf(call), result.messages.first { it.role == "assistant" }.toolCalls)
        assertEquals("receipt", result.messages.last().content)
    }
    @Test fun serialToolIsABarrierForFollowingParallelRead() = runTest {
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val serial = call.copy(id = "serial", name = "mutate")
        val read = call.copy(id = "read", name = "read")
        AgentTurnEngine(model = AgentModel {
            val eager = requireNotNull(currentCoroutineContext()[AgentEagerToolContext])
            eager.offer(serial); eager.offer(read); release.complete(Unit)
            AgentModelReply(toolCalls = listOf(serial, read))
        }, tools = AgentToolExecutor {
            if (it == serial) { release.await(); order += "mutate" } else order += "read"
            AgentToolResult("ok")
        }, isParallelTool = { it.name == "read" }, maxSteps = 1).run("task")
        assertEquals(listOf("mutate", "read"), order)
    }
}
