package com.labteto.dshmobile.harness.agent

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val rawArguments: String = arguments.toString(),
)

data class AgentMessage(
    val role: String,
    val content: String = "",
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: List<AgentToolCall> = emptyList(),
)

data class AgentModelReply(
    val content: String = "",
    val toolCalls: List<AgentToolCall> = emptyList(),
)

enum class AgentStopReason {
    COMPLETED,
    STEP_LIMIT,
}

data class AgentRunResult(
    val turnId: String,
    val answer: String,
    val messages: List<AgentMessage>,
    val steps: Int,
    val stopReason: AgentStopReason,
)

sealed interface AgentEvent {
    val turnId: String

    data class TurnStarted(
        override val turnId: String,
        val input: String,
    ) : AgentEvent

    data class StepStarted(
        override val turnId: String,
        val step: Int,
    ) : AgentEvent

    data class AssistantObserved(
        override val turnId: String,
        val step: Int,
        val content: String,
        val toolCalls: List<AgentToolCall>,
    ) : AgentEvent

    data class ToolStarted(
        override val turnId: String,
        val step: Int,
        val call: AgentToolCall,
    ) : AgentEvent

    data class ToolFinished(
        override val turnId: String,
        val step: Int,
        val call: AgentToolCall,
        val output: String,
    ) : AgentEvent

    data class StepFinished(
        override val turnId: String,
        val step: Int,
    ) : AgentEvent

    data class TurnCompleted(
        override val turnId: String,
        val steps: Int,
        val answer: String,
    ) : AgentEvent

    data class TurnStepLimit(
        override val turnId: String,
        val steps: Int,
    ) : AgentEvent

    data class TurnFailed(
        override val turnId: String,
        val reason: String,
    ) : AgentEvent

    data class TurnCancelled(
        override val turnId: String,
    ) : AgentEvent
}

fun interface AgentModel {
    suspend fun complete(messages: List<AgentMessage>): AgentModelReply
}

fun interface AgentToolExecutor {
    suspend fun execute(call: AgentToolCall): String
}

fun interface AgentToolBatchExecutor {
    suspend fun execute(calls: List<AgentToolCall>): List<String>
}

fun interface AgentEventSink {
    suspend fun append(event: AgentEvent)
}

/**
 * Platform-neutral turn/step loop.
 *
 * Every model-visible mutation is appended to [eventSink] before the next step starts. Cancellation
 * records a cancellation fact and never fabricates a completion event. Android/runtime adapters may
 * provide [toolBatch] to preserve their own bounded parallel scheduling while this core owns the
 * durable turn/step lifecycle.
 */
class AgentLoop(
    private val model: AgentModel,
    private val tools: AgentToolExecutor,
    private val toolBatch: AgentToolBatchExecutor = AgentToolBatchExecutor { calls ->
        calls.map { tools.execute(it) }
    },
    private val isParallelTool: (AgentToolCall) -> Boolean = { false },
    private val eventSink: AgentEventSink = AgentEventSink { },
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    init {
        require(maxSteps in 1..MAX_ALLOWED_STEPS) { "代理步数必须在 1..$MAX_ALLOWED_STEPS 之间" }
    }

    suspend fun run(
        input: String,
        history: List<AgentMessage> = emptyList(),
    ): AgentRunResult {
        val cleanInput = input.trim()
        require(cleanInput.isNotEmpty()) { "用户输入不能为空" }
        val turnId = idFactory()
        val messages = history.toMutableList().apply {
            add(AgentMessage(role = "user", content = cleanInput))
        }
        eventSink.append(AgentEvent.TurnStarted(turnId, cleanInput))

        try {
            repeat(maxSteps) { stepIndex ->
                val step = stepIndex + 1
                eventSink.append(AgentEvent.StepStarted(turnId, step))
                val reply = model.complete(messages.toList())
                requireUniqueCallIds(reply.toolCalls)
                messages += AgentMessage(
                    role = "assistant",
                    content = reply.content,
                    toolCalls = reply.toolCalls,
                )
                eventSink.append(
                    AgentEvent.AssistantObserved(
                        turnId = turnId,
                        step = step,
                        content = reply.content,
                        toolCalls = reply.toolCalls,
                    ),
                )

                if (reply.toolCalls.isEmpty()) {
                    eventSink.append(AgentEvent.StepFinished(turnId, step))
                    eventSink.append(AgentEvent.TurnCompleted(turnId, step, reply.content))
                    return AgentRunResult(
                        turnId = turnId,
                        answer = reply.content,
                        messages = messages.toList(),
                        steps = step,
                        stopReason = AgentStopReason.COMPLETED,
                    )
                }

                var callIndex = 0
                while (callIndex < reply.toolCalls.size) {
                    val first = reply.toolCalls[callIndex]
                    if (!isParallelTool(first)) {
                        eventSink.append(AgentEvent.ToolStarted(turnId, step, first))
                        val output = tools.execute(first)
                        eventSink.append(AgentEvent.ToolFinished(turnId, step, first, output))
                        messages += AgentMessage(
                            role = "tool",
                            content = output,
                            toolCallId = first.id,
                            toolName = first.name,
                        )
                        callIndex += 1
                        continue
                    }

                    val group = reply.toolCalls
                        .drop(callIndex)
                        .takeWhile(isParallelTool)
                    group.forEach { call ->
                        eventSink.append(AgentEvent.ToolStarted(turnId, step, call))
                    }
                    val outputs = toolBatch.execute(group)
                    require(outputs.size == group.size) {
                        "工具批次结果数量不匹配：调用 ${group.size}，结果 ${outputs.size}"
                    }
                    group.zip(outputs).forEach { (call, output) ->
                        eventSink.append(AgentEvent.ToolFinished(turnId, step, call, output))
                        messages += AgentMessage(
                            role = "tool",
                            content = output,
                            toolCallId = call.id,
                            toolName = call.name,
                        )
                    }
                    callIndex += group.size
                }
                eventSink.append(AgentEvent.StepFinished(turnId, step))
            }

            eventSink.append(AgentEvent.TurnStepLimit(turnId, maxSteps))
            return AgentRunResult(
                turnId = turnId,
                answer = messages.lastOrNull { it.role == "assistant" }?.content.orEmpty(),
                messages = messages.toList(),
                steps = maxSteps,
                stopReason = AgentStopReason.STEP_LIMIT,
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                eventSink.append(AgentEvent.TurnCancelled(turnId))
            }
            throw cancelled
        } catch (error: Exception) {
            eventSink.append(
                AgentEvent.TurnFailed(
                    turnId = turnId,
                    reason = error.message ?: error::class.java.simpleName,
                ),
            )
            throw error
        }
    }

    private fun requireUniqueCallIds(calls: List<AgentToolCall>) {
        val duplicate = calls.groupingBy(AgentToolCall::id).eachCount().entries.firstOrNull { it.value > 1 }
        require(duplicate == null) { "同一步出现重复工具调用编号：${duplicate?.key}" }
    }

    private companion object {
        const val DEFAULT_MAX_STEPS = 32
        const val MAX_ALLOWED_STEPS = 128
    }
}
