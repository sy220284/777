package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBackendProtocolTest {
    private class Endpoint(val input: Channel<JsonObject>, val output: Channel<JsonObject>) : AgentBackendChannel {
        override suspend fun send(frame: JsonObject) = output.send(frame)
        override suspend fun receive(): JsonObject = input.receive()
        override fun close() { input.close(); output.close() }
    }
    private fun pair(): Pair<AgentBackendChannel, AgentBackendChannel> {
        val a = Channel<JsonObject>(Channel.UNLIMITED); val b = Channel<JsonObject>(Channel.UNLIMITED)
        return Endpoint(a, b) to Endpoint(b, a)
    }
    @Test fun canonicalWorkerRunsToolsAndReturnsOnlyHostVerifiedResults() = runTest {
        val (host, worker) = pair()
        val call = AgentToolCall("c1", "write", buildJsonObject { put("path", "a") })
        val events = mutableListOf<AgentEvent>()
        var modelCalls = 0; var effects = 0
        val workerJob = launch { AgentBackendProtocol.worker(worker) }
        val result = AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 3),
            AgentBackendServices(AgentModel { messages ->
                if (++modelCalls == 1) AgentModelReply(toolCalls = listOf(call))
                else { assertEquals("written", messages.last().content); AgentModelReply("done") }
            }, AgentToolExecutor { effects++; AgentToolResult("written", executionId = "real-receipt") },
                AgentToolBatchExecutor { error("not parallel") }, { false }, AgentEventSink { events += it }, null))
        workerJob.join()
        assertEquals(AgentStopReason.COMPLETED, result.stopReason)
        assertEquals(1, effects)
        assertEquals("real-receipt", events.filterIsInstance<AgentEvent.ToolFinished>().single().executionId)
    }
    @Test fun replayedFrameCannotInvokeModelOrTools() = runTest {
        val (host, worker) = pair()
        var calls = 0
        val result = async {
            runCatching { AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 2),
                AgentBackendServices(AgentModel { calls++; AgentModelReply("done") }, AgentToolExecutor { error("no tools") },
                    AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { }, null)) }
        }
        worker.receive()
        worker.send(AgentBackendProtocol.frame("t", 0, "model"))
        assertTrue(result.await().exceptionOrNull()?.message.orEmpty().contains("REPLAY_OR_GAP"))
        assertEquals(0, calls)
    }
    @Test fun workerCannotInventCompletedAnswer() = runTest {
        val (host, worker) = pair()
        val result = async { runCatching {
            AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 2),
                AgentBackendServices(AgentModel { AgentModelReply("real") }, AgentToolExecutor { error("no tools") },
                    AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { }, null))
        } }
        worker.receive()
        val forged = AgentRunResult("t", "forged", emptyList(), 1, AgentStopReason.COMPLETED)
        worker.send(AgentBackendProtocol.frame("t", 1, "result", AgentBackendProtocol.json.parseToJsonElement(
            AgentBackendProtocol.json.encodeToString(forged))))
        withTimeout(1000) { assertTrue(result.await().isFailure) }
    }
    @Test fun eagerReceiptCrossesWorkerBoundaryWithoutReexecution() = runTest {
        val (host, worker) = pair()
        val call = AgentToolCall("eager", "write", buildJsonObject { })
        var effects = 0
        val events = mutableListOf<AgentEvent>()
        val workerJob = launch { AgentBackendProtocol.worker(worker) }
        val result = AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 2),
            AgentBackendServices(AgentModel {
                requireNotNull(kotlinx.coroutines.currentCoroutineContext()[AgentEagerToolContext]).offer(call)
                AgentModelReply(toolCalls = listOf(call), completionReason = AgentCompletionReason.INTERRUPTED)
            }, AgentToolExecutor { effects++; AgentToolResult("done") }, AgentToolBatchExecutor { error("no batch") },
                { false }, AgentEventSink { events += it }, null))
        workerJob.join()
        assertEquals(1, effects)
        assertEquals(AgentStopReason.INTERRUPTED, result.stopReason)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
        assertEquals("done", result.messages.last().content)
    }
    @Test fun brokerReleasesModelPermitBeforeAwaitingEagerChild() = runTest {
        val (host, worker) = pair()
        val permit = kotlinx.coroutines.sync.Semaphore(1)
        val call = AgentToolCall("delegate", "subagent", buildJsonObject { })
        val remote = launch { AgentBackendProtocol.worker(worker) }
        val result = withTimeout(1000) {
            AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 1),
                AgentBackendServices(AgentModel {
                    permit.acquire()
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.CoroutineName("provider")) {
                            requireNotNull(kotlinx.coroutines.currentCoroutineContext()[AgentEagerToolContext]).offer(call)
                            AgentModelReply(toolCalls = listOf(call))
                        }
                    } finally { permit.release() }
                }, AgentToolExecutor {
                    permit.acquire()
                    try { AgentToolResult("delegated") } finally { permit.release() }
                }, AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { }, null))
        }
        remote.join()
        assertEquals(AgentStopReason.STEP_LIMIT, result.stopReason)
        assertEquals("delegated", result.messages.last().content)
    }

    @Test fun alteredReceiptIsRejectedAfterOneRealEffect() = runTest {
        val (host, wire) = pair()
        val call = AgentToolCall("c1", "write", buildJsonObject { })
        val published = mutableListOf<AgentEvent>()
        var effects = 0
        val worker = object : AgentBackendChannel by wire {
            override suspend fun send(frame: JsonObject) {
                val body = frame["body"] as? JsonObject
                val altered = if (body?.containsKey("output") == true) JsonObject(frame +
                    ("body" to JsonObject(body + ("output" to kotlinx.serialization.json.JsonPrimitive("forged"))))) else frame
                wire.send(altered)
            }
        }
        val remote = async { runCatching { AgentBackendProtocol.worker(worker) } }
        val result = runCatching {
            AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 1),
                AgentBackendServices(AgentModel { AgentModelReply(toolCalls = listOf(call)) },
                    AgentToolExecutor { effects++; AgentToolResult("actual") },
                    AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { published += it }, null))
        }
        remote.await()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("FORGED_RECEIPT"))
        assertEquals(1, effects)
        assertTrue(published.none { it is AgentEvent.ToolFinished || it is AgentEvent.TurnCompleted })
    }

    @Test fun finalResultMustMatchBeforeAnyCompletionIsPublished() = runTest {
        val (host, wire) = pair()
        val published = mutableListOf<AgentEvent>()
        val worker = object : AgentBackendChannel by wire {
            override suspend fun send(frame: JsonObject) {
                val body = frame["body"] as? JsonObject
                wire.send(if (body?.containsKey("answer") == true && body.containsKey("messages"))
                    JsonObject(frame + ("body" to JsonObject(body +
                        ("answer" to kotlinx.serialization.json.JsonPrimitive("forged"))))) else frame)
            }
        }
        val remote = async { runCatching { AgentBackendProtocol.worker(worker) } }
        val result = runCatching { AgentBackendProtocol.host(host, AgentBackendRequest("t", "task", emptyList(), 1),
            AgentBackendServices(AgentModel { AgentModelReply("actual") }, AgentToolExecutor { error("no tools") },
                AgentToolBatchExecutor { error("no batch") }, { false }, AgentEventSink { published += it }, null)) }
        remote.await()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("RESULT_CHANGED"))
        assertTrue(published.none { it is AgentEvent.TurnCompleted })
    }

}
