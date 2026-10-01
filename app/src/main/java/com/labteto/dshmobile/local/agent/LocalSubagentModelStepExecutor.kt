package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun minimalSubagentRecoveryHistory(history: List<JsonObject>): List<JsonObject>? {
    val latestUserIndex = history.indexOfLast { message ->
        message["role"]?.jsonPrimitive?.contentOrNull == "user"
    }
    if (latestUserIndex < 0) return null
    val prefix = history.take(latestUserIndex).filter { message ->
        message["role"]?.jsonPrimitive?.contentOrNull in setOf("system", "developer")
    }
    return prefix + history[latestUserIndex]
}

internal fun canRetrySubagentStructureFailure(
    error: Throwable,
    history: List<JsonObject>,
): Boolean {
    val modelError = error as? LocalModelException ?: return false
    val structural = modelError.code == "MODEL_HISTORY_INVALID" || modelError.status == 400
    if (!structural) return false

    // Once a tool call/result exists, prior steps may already have mutated external state. A
    // minimal-context retry could cause the model to repeat those side effects, so keep the partial
    // result and fail instead of replaying blindly.
    val hasToolState = history.any { message ->
        val role = message["role"]?.jsonPrimitive?.contentOrNull
        role == "tool" ||
            (role == "assistant" && (message["tool_calls"] as? JsonArray)?.isNotEmpty() == true)
    }
    return !hasToolState && minimalSubagentRecoveryHistory(history) != null
}

/** Owns one subagent model-step request, retry and context-overflow recovery policy. */
internal class LocalSubagentModelStepExecutor(
    private val modelGateway: LocalModelGateway,
    private val modelAttempts: () -> Int,
    private val resourceScheduler: HarnessResourceScheduler,
    private val eventLog: () -> LocalSessionEventLog,
    private val historyCompactor: LocalHistoryCompactor,
) {
    suspend fun complete(
        profile: LocalModelProfile,
        baseUrl: String,
        model: String,
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
        durableHistory: MutableList<JsonObject>? = null,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply {
        val executor = AgentRequestExecutor(
            maxAttempts = modelAttempts().coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is java.io.IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs
                    ?.coerceIn(0L, 60_000L)
                    ?: (1_000L shl (failedAttempt - 1).coerceIn(0, 20))
            },
            eventSink = AgentRequestEventSink { event ->
                when (event) {
                    is AgentRequestEvent.AttemptStarted -> {
                        eventLog().append("subagent/request", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("max_attempts", event.maxAttempts)
                        })
                    }
                    is AgentRequestEvent.AttemptFailed -> {
                        eventLog().append("subagent/request-error", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        })
                    }
                    is AgentRequestEvent.RetryScheduled -> {
                        eventLog().append("subagent/retry", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            put("next_attempt", event.nextAttempt)
                            put("delay_ms", event.delayMillis)
                        })
                    }
                    is AgentRequestEvent.AttemptCancelled -> {
                        eventLog().append("subagent/request-cancelled", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("attempt", event.attempt)
                            event.reason?.let { put("detail", it.take(2_000)) }
                        })
                    }
                    is AgentRequestEvent.AttemptSucceeded -> Unit
                }
            },
        )
        var activeHistory = history
        var overflowRound = 0
        var structureRecoveryAttempted = false
        while (true) {
            try {
                return executor.execute {
                    resourceScheduler.withResource(HarnessResourceKind.MODEL_REQUEST) {
                        try {
                            modelGateway.complete(
                                profile = profile,
                                model = model,
                                baseUrl = baseUrl,
                                messages = activeHistory,
                                tools = tools,
                            )
                        } catch (error: LocalModelException) {
                            eventLog().append("subagent/provider-error", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", step)
                                put("code", error.code)
                                error.status?.let { put("status", it) }
                                error.providerRetryAfterMs?.let { put("retry_after_ms", it) }
                                error.requestId?.let { put("request_id", it) }
                                error.providerCode?.let { put("provider_code", it) }
                                error.providerParam?.let { put("provider_param", it) }
                                error.cause?.let { cause ->
                                    put("cause_type", cause::class.java.simpleName)
                                    cause.message?.takeIf(String::isNotBlank)?.let {
                                        put("cause_detail", it.take(800))
                                    }
                                }
                            })
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!structureRecoveryAttempted && canRetrySubagentStructureFailure(error, activeHistory)) {
                    val minimal = minimalSubagentRecoveryHistory(activeHistory)
                    if (minimal != null && minimal != activeHistory) {
                        structureRecoveryAttempted = true
                        eventLog().append("subagent/history-recovery", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("trigger", (error as? LocalModelException)?.code ?: "HTTP_400")
                            put("messages_before", activeHistory.size)
                            put("messages_after", minimal.size)
                        })
                        activeHistory = minimal
                        continue
                    }
                }
                if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
                val compacted = historyCompactor.compactForOverflow(
                    activeHistory,
                    LocalHistorySummaryMode.WORK,
                ) ?: throw error
                val madeProgress =
                    compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                        compacted.messages != activeHistory
                if (!madeProgress) throw error

                overflowRound += 1
                durableHistory?.let { durable ->
                    applyOverflowCompaction(
                        history = durable,
                        compactor = historyCompactor,
                        summaryMode = LocalHistorySummaryMode.WORK,
                    )?.let { durableCompaction ->
                        eventLog().append("subagent/compaction", buildJsonObject {
                            put("agent_id", subagentId)
                            put("trigger", "context-overflow")
                            put("round", overflowRound)
                            put("omitted_messages", durableCompaction.omittedMessages)
                            put("summary", durableCompaction.summary)
                            put("estimated_tokens_before", durableCompaction.estimatedTokensBefore)
                            put("estimated_tokens_after", durableCompaction.estimatedTokensAfter)
                        })
                    }
                }
                eventLog().append("subagent/context-overflow-recovery", buildJsonObject {
                    put("agent_id", subagentId)
                    put("step", step)
                    put("round", overflowRound)
                    put("model", model)
                    put("estimated_tokens_before", compacted.estimatedTokensBefore)
                    put("estimated_tokens_after", compacted.estimatedTokensAfter)
                    put("omitted_messages", compacted.omittedMessages)
                })
                activeHistory = compacted.messages
            }
        }
    }
}
