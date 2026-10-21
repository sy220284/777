package com.labteto.dshmobile.harness.agent

import com.labteto.dshmobile.harness.tools.ToolResultRetention
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val completionReason: AgentCompletionReason = AgentCompletionReason.COMPLETED,
    val rawStopReason: String? = null,
)

enum class AgentCompletionReason { COMPLETED, OUTPUT_LIMIT, REFUSED, INTERRUPTED }

/** Provider-reported facts only; the runtime usage owner remains responsible for billing once. */
data class AgentModelUsage(
    val promptTokens: Long, val completionTokens: Long,
    val cacheHitTokens: Long = 0L, val cacheMissTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L, val reasoningTokens: Long = 0L,
)

data class AgentModelReply(
    val content: String = "",
    val toolCalls: List<AgentToolCall> = emptyList(),
    val completionReason: AgentCompletionReason = AgentCompletionReason.COMPLETED,
    val rawStopReason: String? = null,
    val reportedUsage: AgentModelUsage? = null,
)

enum class AgentToolSideEffect {
    NONE,
    POSSIBLE,
}

data class AgentToolResult(
    val content: String,
    val isError: Boolean = false,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    val sideEffect: AgentToolSideEffect = AgentToolSideEffect.NONE,
    val recoveryHint: String? = null,
    val retention: ToolResultRetention = ToolResultRetention.DURABLE,
    val processExitCode: Int? = null,
    val fileVersions: Map<String, String> = emptyMap(),
    val executionId: String? = null,
)

fun AgentToolResult.modelVisibleContent(): String {
    if (!isError) return content
    return buildJsonObject {
        put("status", "error")
        put("error_code", errorCode ?: "TOOL_ERROR")
        put("retryable", retryable)
        put("side_effect", sideEffect.name.lowercase())
        recoveryHint?.takeIf(String::isNotBlank)?.let { put("recovery_hint", it) }
        put("content", content)
    }.toString()
}

enum class AgentStopReason {
    COMPLETED,
    OUTPUT_LIMIT,
    REFUSED,
    INTERRUPTED,
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
        val completionReason: AgentCompletionReason = AgentCompletionReason.COMPLETED,
        val rawStopReason: String? = null,
        val reportedUsage: AgentModelUsage? = null,
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
        val isError: Boolean = false,
        val errorCode: String? = null,
        val retryable: Boolean = false,
        val sideEffect: AgentToolSideEffect = AgentToolSideEffect.NONE,
        val recoveryHint: String? = null,
        val retention: ToolResultRetention = ToolResultRetention.DURABLE,
        val processExitCode: Int? = null,
        val fileVersions: Map<String, String> = emptyMap(),
        val executionId: String? = null,
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
        val completionReason: AgentCompletionReason? = null,
    ) : AgentEvent

    data class TurnCancelled(
        override val turnId: String,
    ) : AgentEvent
}

fun interface AgentModel {
    suspend fun complete(messages: List<AgentMessage>): AgentModelReply
}

fun interface AgentToolExecutor {
    suspend fun execute(call: AgentToolCall): AgentToolResult
}

fun interface AgentToolBatchExecutor {
    suspend fun execute(calls: List<AgentToolCall>): List<AgentToolResult>
}

fun interface AgentEventSink {
    suspend fun append(event: AgentEvent)
}

/**
 * Allows a runtime adapter to treat the Agent Turn Engine's configured step limit as a soft budget.
 *
 * Returning a value greater than [currentLimit] extends the same turn without resetting step
 * numbering or losing tool/model history. Returning null (or a non-growing value) keeps the
 * original hard-stop behaviour.
 */
fun interface AgentStepLimitExtender {
    fun extend(currentLimit: Int, stepsUsed: Int): Int?
}
