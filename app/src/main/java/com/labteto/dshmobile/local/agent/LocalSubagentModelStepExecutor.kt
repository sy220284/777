package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.model.LocalModelGateway
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
        var structureRecoveryUsed = false
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
                val modelError = error as? LocalModelException
                if (
                    !structureRecoveryUsed &&
                    modelError?.code == "MODEL_HISTORY_INVALID"
                ) {
                    val recovered = minimalSafeHistory(activeHistory)
                    if (recovered != activeHistory) {
                        structureRecoveryUsed = true
                        eventLog().append("subagent/history-recovery", buildJsonObject {
                            put("agent_id", subagentId)
                            put("step", step)
                            put("reason", modelError.code)
                            put("messages_before", activeHistory.size)
                            put("messages_after", recovered.size)
                        })
                        activeHistory = recovered
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

    private fun minimalSafeHistory(history: List<JsonObject>): List<JsonObject> {
        val lastUserIndex = history.indexOfLast { message ->
            (message["role"] as? JsonPrimitive)?.contentOrNull == "user"
        }
        if (lastUserIndex < 0) return history

        val leadingSystem = history.takeWhile { message ->
            (message["role"] as? JsonPrimitive)?.contentOrNull == "system"
        }
        return buildList {
            addAll(leadingSystem)
            if (lastUserIndex >= leadingSystem.size) add(history[lastUserIndex])
        }
    }
}
