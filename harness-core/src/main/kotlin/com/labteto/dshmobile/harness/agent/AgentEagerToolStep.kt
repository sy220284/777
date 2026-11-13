package com.labteto.dshmobile.harness.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only provider-completed immutable items enter this context. Partial argument deltas never do. */
class AgentEagerToolContext internal constructor(
    val offer: suspend (AgentToolCall) -> Unit,
    val hasOffered: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentEagerToolContext>
}

/** Per-step dispatch cache, not another durable ledger: the injected executor owns effect receipts. */
internal class AgentEagerToolStep(
    private val scope: CoroutineScope,
    private val turnId: String,
    private val step: Int,
    private val tools: AgentToolExecutor,
    private val emit: AgentEventSink,
    private val mayRunParallel: (AgentToolCall) -> Boolean,
) {
    private class Entry(val call: AgentToolCall, val result: Deferred<AgentToolResult>)
    private val entries = linkedMapOf<String, Entry>()
    private val mutex = Mutex()
    @Volatile private var offered = false
    private var lastSerial: Deferred<AgentToolResult>? = null
    val context = AgentEagerToolContext(offer = { call ->
        mutex.withLock {
            val previous = entries[call.id]
            if (previous != null) {
                check(previous.call == call) { "EAGER_TOOL_ITEM_CHANGED" }
            } else {
                require(call.id.isNotBlank() && call.name.isNotBlank())
                currentCoroutineContext()[AgentRunExecutionContext]?.requireCurrent()
                emit.append(AgentEvent.ToolStarted(turnId, step, call))
                val parallel = mayRunParallel(call)
                val predecessors = if (parallel) listOfNotNull(lastSerial) else entries.values.map { it.result }
                // Tool children belong to the step/activation, not the provider lease.
                val task = scope.async {
                    predecessors.forEach { it.await() }
                    currentCoroutineContext().ensureActive()
                    tools.execute(call)
                }
                entries[call.id] = Entry(call, task)
                offered = true
                if (!parallel) lastSerial = task
            }
        }
    }, hasOffered = { offered })
    suspend fun contains(call: AgentToolCall): Boolean = mutex.withLock {
        entries[call.id]?.let { check(it.call == call) { "EAGER_TOOL_ITEM_CHANGED" }; true } ?: false
    }
    suspend fun result(call: AgentToolCall): AgentToolResult? {
        val entry = mutex.withLock { entries[call.id] } ?: return null
        check(entry.call == call) { "EAGER_TOOL_ITEM_CHANGED" }
        return entry.result.await()
    }
    suspend fun validate(reply: AgentModelReply): AgentModelReply = mutex.withLock {
        val admitted = entries.values.map { it.call }
        check(reply.toolCalls.take(admitted.size) == admitted) { "EAGER_TOOL_FINAL_SNAPSHOT_CHANGED" }
        if (reply.completionReason == AgentCompletionReason.COMPLETED) reply
        else reply.copy(toolCalls = admitted)
    }
}
