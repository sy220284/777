package com.labteto.dshmobile.harness.agent

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope

/**
 * Kotlin native Turn executor shared by foreground Work, child Agents and
 * reference conformance. The engine owns the Step lifecycle only; a Work Goal,
 * the Tool's external side effect and a Session's lease retain their own owners.
 *
 * This is a new production engine, not a delegate to the former AgentLoop.
 */
class AgentTurnEngine(
    private val model: AgentModel,
    private val tools: AgentToolExecutor,
    private val toolBatch: AgentToolBatchExecutor = AgentToolBatchExecutor { calls ->
        calls.map { tools.execute(it) }
    },
    private val isParallelTool: (AgentToolCall) -> Boolean = { false },
    private val eventSink: AgentEventSink = AgentEventSink { },
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val stepLimitExtender: AgentStepLimitExtender? = null,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val backend: AgentTurnBackend? = null,
    private val suspendStepLimitExtender: (suspend (Int, Int) -> Int?)? = null,
) {
    private val toolDispatch = AgentToolDispatch(tools, toolBatch, isParallelTool, eventSink)
    init { require(maxSteps > 0) { "代理步数必须大于 0" } }

    suspend fun run(
        input: String,
        history: List<AgentMessage> = emptyList(),
    ): AgentRunResult {
        val command = input.trim()
        require(command.isNotEmpty()) { "用户输入不能为空" }
        backend?.let { delegate ->
            currentCoroutineContext()[AgentRunExecutionContext]?.requireCurrent()
            val extend: (suspend (Int, Int) -> Int?)? =
                if (suspendStepLimitExtender == null && stepLimitExtender == null) null else { limit, used ->
                    if (suspendStepLimitExtender != null) suspendStepLimitExtender.invoke(limit, used)
                    else stepLimitExtender?.extend(limit, used)
                }
            return delegate.run(AgentBackendRequest(idFactory(), command, history, maxSteps),
                AgentBackendServices(model, tools, toolBatch, isParallelTool, eventSink, extend))
                .also { currentCoroutineContext()[AgentRunExecutionContext]?.requireCurrent() }
        }
        val turn = AgentTurnState(idFactory(), command, history)
        val budget = AgentTurnStepBudget(maxSteps, stepLimitExtender, suspendStepLimitExtender)

        val execution = currentCoroutineContext()[AgentRunExecutionContext]
        execution?.requireCurrent()
        eventSink.append(AgentEvent.TurnStarted(turn.turnId, command))
        try {
            while (budget.admits(turn.steps + 1)) {
                execution?.requireCurrent()
                val step = turn.startStep()
                eventSink.append(AgentEvent.StepStarted(turn.turnId, step))
                coroutineScope {
                val eager = AgentEagerToolStep(turn.turnId, step, tools, eventSink, isParallelTool)
                val observed = withContext(eager.context) { model.complete(turn.snapshot()) }
                // An interrupted response may settle already-admitted items, never start new ones.
                val reply = eager.validate(observed)
                execution?.requireCurrent()
                turn.acceptModelReply(reply)
                eventSink.append(AgentEvent.AssistantObserved(
                    turnId = turn.turnId,
                    step = step,
                    content = reply.content,
                    toolCalls = reply.toolCalls,
                    completionReason = reply.completionReason, rawStopReason = reply.rawStopReason,
                    reportedUsage = reply.reportedUsage,
                ))
                if (reply.toolCalls.isNotEmpty()) {
                    toolDispatch.executeStep(turn, step, reply.toolCalls, eager)
                }
                eventSink.append(AgentEvent.StepFinished(turn.turnId, step))
                turn.closeStep()

                if (reply.completionReason != AgentCompletionReason.COMPLETED) {
                    eventSink.append(AgentEvent.TurnFailed(turn.turnId,
                        reply.rawStopReason ?: reply.completionReason.name, reply.completionReason))
                    return@coroutineScope turn.result(AgentStopReason.valueOf(reply.completionReason.name))
                }
                if (reply.toolCalls.isEmpty()) {
                    eventSink.append(AgentEvent.TurnCompleted(turn.turnId, step, reply.content))
                    return@coroutineScope turn.result(AgentStopReason.COMPLETED)
                }
                null
                }?.let { return it }
            }
            eventSink.append(AgentEvent.TurnStepLimit(turn.turnId, turn.steps))
            return turn.result(AgentStopReason.STEP_LIMIT)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                try {
                    eventSink.append(AgentEvent.TurnCancelled(turn.turnId))
                } catch (terminalError: Exception) {
                    if (terminalError !== cancelled) cancelled.addSuppressed(terminalError)
                }
            }
            throw cancelled
        } catch (error: Exception) {
            try {
                eventSink.append(AgentEvent.TurnFailed(
                    turnId = turn.turnId,
                    reason = error.message ?: error::class.java.simpleName,
                ))
            } catch (terminalError: Exception) {
                if (terminalError !== error) error.addSuppressed(terminalError)
            }
            throw error
        }
    }

    private companion object {
        const val DEFAULT_MAX_STEPS = 32
    }
}
