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

data class AgentRunResult(
    val turnId: String,
    val answer: String,
    val messages: List<AgentMessage>,
    val steps: Int,
)

sealed interface AgentEvent {
    val turnId: String

    data class TurnStarted(
        override val turnId: String,
        val input: String,
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

    data class TurnCompleted(
        override val turnId: String,
        val steps: Int,
        val answer: String,
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

fun interface AgentEventSink {
    suspend fun append(event: AgentEvent)
}

/**
 * Platform-neutral turn/step loop.
 *
 * Every model-visible mutation is appended to [eventSink] before the next step starts. Cancellation
 * records a cancellation fact and never fabricates a completion event.
 */
class AgentLoop(
    private val model: AgentModel,
    private val tools: AgentToolExecutor,
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
                    eventSink.append(AgentEvent.TurnCompleted(turnId, step, reply.content))
                    return AgentRunResult(turnId, reply.content, messages.toList(), step)
                }

                reply.toolCalls.forEach { call ->
                    eventSink.append(AgentEvent.ToolStarted(turnId, step, call))
                    val output = tools.execute(call)
                    eventSink.append(AgentEvent.ToolFinished(turnId, step, call, output))
                    messages += AgentMessage(
                        role = "tool",
                        content = output,
                        toolCallId = call.id,
                        toolName = call.name,
                    )
                }
            }
            error("代理超过最大步数：$maxSteps")
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
