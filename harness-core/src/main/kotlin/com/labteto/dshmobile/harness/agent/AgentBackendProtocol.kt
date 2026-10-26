package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

/** One connection is one activation; there is no reconnect/replay of an uncertain tool request. */
interface AgentBackendChannel : AutoCloseable {
    suspend fun send(frame: JsonObject)
    suspend fun receive(): JsonObject
}

object AgentBackendProtocol {
    const val VERSION = 1
    val json = Json { encodeDefaults = true; classDiscriminator = "kind" }
    private inline fun <reified T> encode(value: T): JsonElement = json.parseToJsonElement(json.encodeToString(value))
    private inline fun <reified T> decode(value: JsonElement): T = json.decodeFromString(value.toString())

    fun frame(turn: String, sequence: Long, operation: String, body: JsonElement = JsonNull): JsonObject = buildJsonObject {
        put("version", VERSION); put("turn", turn); put("sequence", sequence); put("operation", operation); put("body", body)
    }

    private fun checkFrame(frame: JsonObject, turn: String) {
        require(frame["version"]?.jsonPrimitive?.intOrNull == VERSION &&
            frame["turn"]?.jsonPrimitive?.content == turn) { "AGENT_BACKEND_IDENTITY_MISMATCH" }
    }

    /** Runs in the separate UID/process or authenticated remote JVM, using the same canonical engine. */
    suspend fun worker(
        channel: AgentBackendChannel,
        environmentFactory: (suspend (AgentBackendRequest) -> AgentEnvironmentTools)? = null,
    ): Unit = coroutineScope {
        val incoming = Channel<JsonObject>(Channel.BUFFERED)
        val settled = kotlinx.coroutines.CompletableDeferred<Unit>()
        // A permanent receive owner also notices EOF while an environment command is running.
        // Its failure cancels that command and all processes owned by this activation.
        val receiver = launch {
            try { while (true) incoming.send(channel.receive()) }
            catch (failure: Exception) { if (!settled.isCompleted) throw failure }
            finally { incoming.close() }
        }
        var environment: AgentEnvironmentTools? = null
        try {
            workerOwned(channel, incoming, { settled.complete(Unit) }) { request ->
                if (request.remoteTools) requireNotNull(environmentFactory) {
                    "AGENT_REMOTE_ENVIRONMENT_UNAVAILABLE"
                }.invoke(request).also { environment = it } else null
            }
        } finally {
            try { withContext(NonCancellable) { environment?.close() } }
            finally { receiver.cancel(); channel.close() }
        }
    }

    private suspend fun workerOwned(
        channel: AgentBackendChannel, incoming: Channel<JsonObject>, onResult: () -> Unit,
        environmentFor: suspend (AgentBackendRequest) -> AgentEnvironmentTools?,
    ) {
        val start = incoming.receive()
        require(start["operation"]?.jsonPrimitive?.content == "start") { "AGENT_BACKEND_START_REQUIRED" }
        val request = decode<AgentBackendRequest>(start.getValue("body"))
        checkFrame(start, request.turnId)
        require(start["sequence"]?.jsonPrimitive?.longOrNull == 0L)
        val environment = environmentFor(request)
        var sequence = 0L
        var environmentSequence = 0L
        val rpcMutex = Mutex()
        suspend fun rpc(operation: String, body: JsonElement): JsonElement = rpcMutex.withLock {
            val id = ++sequence
            channel.send(frame(request.turnId, id, operation, body))
            var answer: JsonElement? = null
            while (answer == null) {
                val reply = incoming.receive()
                checkFrame(reply, request.turnId)
                if (reply["operation"]?.jsonPrimitive?.content == "heartbeat") continue
                if (reply["operation"]?.jsonPrimitive?.content == "environment") {
                    check(reply["sequence"]?.jsonPrimitive?.longOrNull == ++environmentSequence) {
                        "AGENT_ENVIRONMENT_REPLAY_OR_GAP"
                    }
                    val invocation = decode<AgentEnvironmentToolRequest>(reply.getValue("body"))
                    val executor = requireNotNull(environment) { "AGENT_REMOTE_ENVIRONMENT_NOT_ADMITTED" }
                    val result = try { executor.execute(invocation) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        com.labteto.dshmobile.harness.tools.ToolResult(
                            failure.message ?: "Remote tool failed", isError = true,
                            errorCode = "REMOTE_TOOL_FAILED", retryable = false,
                            recoveryHint = "Inspect the remote workspace before repeating a mutation.",
                        )
                    }
                    channel.send(frame(request.turnId, reply.getValue("sequence").jsonPrimitive.long,
                        "environment_result", buildJsonObject {
                            put("execution_id", invocation.executionId); put("result", encode(result))
                        }))
                    continue
                }
                check(reply["operation"]?.jsonPrimitive?.content == "reply" &&
                    reply["sequence"]?.jsonPrimitive?.longOrNull == id) { "AGENT_BACKEND_REPLY_ORDER" }
                reply["error"]?.jsonPrimitive?.contentOrNull?.let { error(it) }
                answer = reply.getValue("body")
            }
            requireNotNull(answer)
        }
        var parallelIds = emptySet<String>()
        val engine = AgentTurnEngine(
            model = AgentModel { messages ->
                val response = rpc("model", encode(messages)).jsonObject
                parallelIds = response.getValue("parallel_ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
                response.getValue("eager_calls").jsonArray.forEach { item ->
                    requireNotNull(currentCoroutineContext()[AgentEagerToolContext]).offer(decode(item))
                }
                decode<AgentModelReply>(response.getValue("reply"))
            },
            tools = AgentToolExecutor { decode<List<AgentToolResult>>(rpc("tools", buildJsonObject {
                put("parallel", false); put("calls", encode(listOf(it)))
            })).single() },
            toolBatch = AgentToolBatchExecutor { calls -> decode(rpc("tools", buildJsonObject {
                put("parallel", true); put("calls", encode(calls))
            })) },
            isParallelTool = { it.id in parallelIds },
            eventSink = AgentEventSink { rpc("event", encode(it)) },
            maxSteps = request.maxSteps,
            suspendStepLimitExtender = { limit, used ->
                rpc("extend", buildJsonObject { put("limit", limit); put("used", used) }).jsonPrimitive.intOrNull
            },
            idFactory = { request.turnId },
        )
        val result = engine.run(request.input, request.history)
        onResult()
        channel.send(frame(request.turnId, ++sequence, "result", encode(result)))
    }

    /** Host broker verifies worker events against actual model/tool receipts before publishing. */
    suspend fun host(
        channel: AgentBackendChannel, request: AgentBackendRequest, services: AgentBackendServices,
        requireEnvironmentGrant: suspend () -> Unit = {},
    ): AgentRunResult {
        if (!request.remoteTools) return coroutineScope { hostOwned(channel, request, services, this) }
        val environmentMutex = Mutex()
        var environmentSequence = 0L
        val environment = com.labteto.dshmobile.harness.tools.ToolExecutionEnvironmentContext { tool, context, input, raw ->
            environmentMutex.withLock {
                requireEnvironmentGrant()
                val invocation = requireNotNull(currentCoroutineContext()[com.labteto.dshmobile.harness.tools.ToolInvocationContext]) {
                    "AGENT_ENVIRONMENT_INVOCATION_REQUIRED"
                }
                val id = ++environmentSequence
                val admitted = AgentEnvironmentToolRequest(tool.name, input, raw, context.allowMutation,
                    context.sessionId, invocation.callId, invocation.executionId)
                channel.send(frame(request.turnId, id, "environment", encode(admitted)))
                val reply = channel.receive()
                checkFrame(reply, request.turnId)
                check(reply["operation"]?.jsonPrimitive?.content == "environment_result" &&
                    reply["sequence"]?.jsonPrimitive?.longOrNull == id &&
                    reply.getValue("body").jsonObject["execution_id"]?.jsonPrimitive?.content == invocation.executionId) {
                    "AGENT_ENVIRONMENT_RECEIPT_MISMATCH"
                }
                requireEnvironmentGrant()
                decode<com.labteto.dshmobile.harness.tools.ToolResult>(reply.getValue("body").jsonObject.getValue("result"))
            }
        }
        return withContext(environment) { coroutineScope { hostOwned(channel, request, services, this) } }
    }

    private suspend fun hostOwned(channel: AgentBackendChannel, request: AgentBackendRequest,
        services: AgentBackendServices, scope: CoroutineScope): AgentRunResult {
        val state = AgentTurnState(request.turnId, request.input.trim(), request.history)
        var sequence = 0L
        var started = false
        var modelReply: AgentModelReply? = null
        var observed = false
        var stepClosed = true
        var extensionDenied = false
        var eager: AgentEagerToolStep? = null
        val delivered = mutableSetOf<String>()
        var declared = 0
        var finished = 0
        var limit = request.maxSteps
        var terminal: AgentStopReason? = null
        var terminalEvent: AgentEvent? = null
        var heartbeat: Job? = null
        val receipts = linkedMapOf<String, AgentToolResult>()
        // Close the wire as soon as the activation is cancelled, even while an audited
        // commit handler is finishing its receipt in NonCancellable. That receipt may be
        // uncertain; cancellation never keeps the remote process lease alive.
        val cancellationCloser = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { channel.close() }
        }
        suspend fun publish(event: AgentEvent) {
            require(event.turnId == request.turnId) { "AGENT_BACKEND_EVENT_IDENTITY" }
            check(terminal == null) { "AGENT_BACKEND_ALREADY_TERMINAL" }
            when (event) {
                is AgentEvent.TurnStarted -> { check(!started && event.input == request.input.trim()); started = true }
                is AgentEvent.StepStarted -> {
                    check(started && stepClosed && (modelReply == null || (requireNotNull(modelReply).toolCalls.isNotEmpty() && requireNotNull(modelReply).completionReason == AgentCompletionReason.COMPLETED)) && terminal == null && event.step == state.steps + 1 && event.step <= limit)
                    state.startStep(); stepClosed = false; extensionDenied = false; delivered.clear()
                    eager = AgentEagerToolStep(scope, request.turnId, event.step, services.tools, services.events, services.isParallel)
                    modelReply = null; observed = false; declared = 0; finished = 0; receipts.clear()
                }
                is AgentEvent.AssistantObserved -> {
                    val reply = requireNotNull(modelReply)
                    check(!stepClosed && !observed && event.step == state.steps && event.content == reply.content &&
                        event.toolCalls == reply.toolCalls && event.completionReason == reply.completionReason &&
                        event.rawStopReason == reply.rawStopReason && event.reportedUsage == reply.reportedUsage)
                    observed = true
                }
                is AgentEvent.ToolStarted -> {
                    check(!stepClosed && (observed || eager?.contains(event.call) == true) && event.step == state.steps && requireNotNull(modelReply).toolCalls.getOrNull(declared) == event.call)
                    declared++
                }
                is AgentEvent.ToolFinished -> {
                    check(!stepClosed && observed && finished < declared && event.step == state.steps && requireNotNull(modelReply).toolCalls.getOrNull(finished) == event.call)
                    val actual = requireNotNull(receipts[event.call.id]) { "AGENT_BACKEND_RECEIPT_MISSING" }
                    check(AgentToolResult(event.output, event.isError, event.errorCode, event.retryable, event.sideEffect,
                        event.recoveryHint, event.retention, event.processExitCode, event.fileVersions, event.executionId, event.richContent) == actual) {
                        "AGENT_BACKEND_FORGED_RECEIPT"
                    }
                    finished++
                }
                is AgentEvent.StepFinished -> {
                    check(!stepClosed && observed && event.step == state.steps && finished == requireNotNull(modelReply).toolCalls.size)
                }
                is AgentEvent.TurnCompleted -> {
                    val reply = requireNotNull(modelReply)
                    check(stepClosed && observed && reply.toolCalls.isEmpty() && reply.completionReason == AgentCompletionReason.COMPLETED &&
                        event.steps == state.steps && event.answer == reply.content)
                    terminal = AgentStopReason.COMPLETED
                }
                is AgentEvent.TurnStepLimit -> {
                    check(stepClosed && extensionDenied && state.steps == limit && finished == requireNotNull(modelReply).toolCalls.size && event.steps == state.steps)
                    terminal = AgentStopReason.STEP_LIMIT
                }
                is AgentEvent.TurnFailed -> {
                    val reason = event.completionReason
                    check(reason != null) { "AGENT_BACKEND_WORKER_FAILED" }
                    val reply = requireNotNull(modelReply)
                    check(stepClosed && observed && reason != AgentCompletionReason.COMPLETED && reply.completionReason == reason &&
                        event.reason == (reply.rawStopReason ?: reason.name))
                    terminal = AgentStopReason.valueOf(reason.name)
                }
                is AgentEvent.TurnCancelled -> error("AGENT_BACKEND_WORKER_CANCELLED")
            }
            if (terminal != null) terminalEvent = event
            else if (event !is AgentEvent.ToolStarted || eager?.contains(event.call) != true) services.events.append(event)
            when (event) {
                is AgentEvent.ToolFinished -> state.appendToolResult(event.call, requireNotNull(receipts[event.call.id]))
                is AgentEvent.StepFinished -> { state.closeStep(); stepClosed = true }
                else -> Unit
            }
        }
        try {
            val encodedRequest = encode(request).jsonObject.let { value ->
                // Strict legacy workers know the original four fields. Preserve their
                // control-loop mode; opting into remote tools requires a capable worker.
                if (request.remoteTools) value else JsonObject(value - "remoteTools")
            }
            channel.send(frame(request.turnId, 0, "start", encodedRequest))
            heartbeat = CoroutineScope(currentCoroutineContext()).launch {
                while (isActive) { delay(20_000); channel.send(frame(request.turnId, 0, "heartbeat")) }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                currentCoroutineContext()[AgentRunExecutionContext]?.requireCurrent()
                val incoming = channel.receive()
                checkFrame(incoming, request.turnId)
                check(incoming["sequence"]?.jsonPrimitive?.longOrNull == ++sequence) { "AGENT_BACKEND_REPLAY_OR_GAP" }
                val operation = incoming["operation"]?.jsonPrimitive?.content
                check(terminal == null || operation == "result") { "AGENT_BACKEND_ALREADY_TERMINAL" }
                val body = incoming.getValue("body")
                val result: JsonElement = when (operation) {
                    "model" -> {
                        check(started && !stepClosed && modelReply == null && decode<List<AgentMessage>>(body) == state.snapshot()) { "AGENT_BACKEND_MODEL_HISTORY_CHANGED" }
                        val activeEager = requireNotNull(eager)
                        val actual = withContext(activeEager.context) { services.model.complete(state.snapshot()) }
                        val settled = activeEager.validate(actual)
                        val eagerCalls = settled.toolCalls.filter { activeEager.contains(it) }
                        for (call in eagerCalls) receipts[call.id] = requireNotNull(activeEager.result(call))
                        state.acceptModelReply(settled); modelReply = settled
                        buildJsonObject {
                            put("reply", encode(settled)); put("eager_calls", encode(eagerCalls)); put("parallel_ids", encode(settled.toolCalls.filter(services.isParallel).map { it.id }))
                        }
                    }
                    "tools" -> {
                        val values = body.jsonObject
                        val calls = decode<List<AgentToolCall>>(values.getValue("calls"))
                        val expected = requireNotNull(modelReply).toolCalls.take(declared).filterNot { it.id in delivered }
                        check((observed || calls.all { eager?.contains(it) == true }) && calls.isNotEmpty() && calls == expected.take(calls.size) && calls.none { it.id in delivered }) { "AGENT_BACKEND_TOOL_NOT_ADMITTED" }
                        val parallel = values.getValue("parallel").jsonPrimitive.boolean
                        check(if (parallel) calls.all(services.isParallel) else calls.size == 1 && (eager?.contains(calls.single()) == true || !services.isParallel(calls.single())))
                        val pending = calls.filterNot { it.id in receipts }
                        val fresh = if (pending.isEmpty()) emptyList() else if (parallel) services.batch.execute(pending) else listOf(services.tools.execute(pending.single()))
                        pending.indices.forEach { receipts[pending[it].id] = fresh[it] }
                        val actual = calls.map { requireNotNull(receipts[it.id]) }
                        delivered.addAll(calls.map { it.id })
                        check(actual.size == calls.size)
                        calls.indices.forEach { receipts[calls[it].id] = actual[it] }
                        encode(actual)
                    }
                    "event" -> { publish(decode(body)); JsonNull }
                    "extend" -> {
                        check(stepClosed && observed && !extensionDenied && body.jsonObject["limit"]?.jsonPrimitive?.intOrNull == limit &&
                            body.jsonObject["used"]?.jsonPrimitive?.intOrNull == state.steps && state.steps == limit)
                        val expanded = services.extend?.invoke(limit, state.steps)?.takeIf { it > limit }
                        if (expanded != null) limit = expanded else extensionDenied = true
                        expanded?.let(::JsonPrimitive) ?: JsonNull
                    }
                    "result" -> {
                        val actual = decode<AgentRunResult>(body)
                        val expected = state.result(requireNotNull(terminal) { "AGENT_BACKEND_TERMINAL_MISSING" })
                        check(actual == expected) { "AGENT_BACKEND_RESULT_CHANGED" }
                        services.events.append(requireNotNull(terminalEvent))
                        return actual
                    }
                    else -> error("AGENT_BACKEND_OPERATION_UNKNOWN")
                }
                channel.send(frame(request.turnId, sequence, "reply", result))
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { services.events.append(AgentEvent.TurnCancelled(request.turnId)) } }
            throw cancelled
        } catch (failure: Exception) {
            runCatching { services.events.append(AgentEvent.TurnFailed(request.turnId, "后端连接或执行中断；已执行工具须依据原回执核实")) }
            throw failure
        } finally { heartbeat?.cancel(); cancellationCloser.cancel(); channel.close() }
    }
}
