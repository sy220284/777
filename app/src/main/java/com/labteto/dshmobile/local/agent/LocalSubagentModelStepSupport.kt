package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.local.LocalHistoryCompactor
import com.labteto.dshmobile.local.LocalHistorySummaryMode
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.applyOverflowCompaction
import com.labteto.dshmobile.local.contextWindowExceeded
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Diagnostics and bounded recovery policy for one subagent model step. */
internal class LocalSubagentModelStepSupport(
    private val eventLog: () -> LocalSessionEventLog,
    private val historyCompactor: LocalHistoryCompactor,
) {
    fun requestEvents(subagentId: String, step: Int): AgentRequestEventSink =
        AgentRequestEventSink { event ->
            val (type, data) = when (event) {
                is AgentRequestEvent.AttemptStarted -> "subagent/request" to buildJsonObject {
                    put("agent_id", subagentId); put("step", step)
                    put("attempt", event.attempt); put("max_attempts", event.maxAttempts)
                }
                is AgentRequestEvent.AttemptFailed -> "subagent/request-error" to buildJsonObject {
                    put("agent_id", subagentId); put("step", step); put("attempt", event.attempt)
                    put("retryable", event.retryable); put("will_retry", event.willRetry)
                    put("detail", event.reason.take(2_000))
                }
                is AgentRequestEvent.RetryScheduled -> "subagent/retry" to buildJsonObject {
                    put("agent_id", subagentId); put("step", step); put("attempt", event.attempt)
                    put("next_attempt", event.nextAttempt); put("delay_ms", event.delayMillis)
                }
                is AgentRequestEvent.AttemptCancelled -> "subagent/request-cancelled" to buildJsonObject {
                    put("agent_id", subagentId); put("step", step); put("attempt", event.attempt)
                    event.reason?.let { put("detail", it.take(2_000)) }
                }
                is AgentRequestEvent.AttemptSucceeded -> return@AgentRequestEventSink
            }
            eventLog().append(type, data)
        }

    fun providerError(subagentId: String, step: Int, error: LocalModelException) {
        eventLog().append("subagent/provider-error", buildJsonObject {
            put("agent_id", subagentId); put("step", step); put("code", error.code)
            error.status?.let { put("status", it) }
            error.providerRetryAfterMs?.let { put("retry_after_ms", it) }
            error.requestId?.let { put("request_id", it) }
            error.providerCode?.let { put("provider_code", it) }
            error.providerParam?.let { put("provider_param", it) }
            error.cause?.let { cause ->
                put("cause_type", cause::class.java.simpleName)
                cause.message?.takeIf(String::isNotBlank)?.let { put("cause_detail", it.take(800)) }
            }
        })
    }

    fun recoverStructure(
        history: List<JsonObject>,
        subagentId: String,
        step: Int,
        error: Throwable,
        alreadyUsed: Boolean,
    ): List<JsonObject>? {
        val modelError = error as? LocalModelException ?: return null
        if (alreadyUsed || modelError.code != "MODEL_HISTORY_INVALID") return null
        val lastUserIndex = history.indexOfLast {
            (it["role"] as? JsonPrimitive)?.contentOrNull == "user"
        }
        if (lastUserIndex < 0) return null
        val leadingSystem = history.takeWhile {
            (it["role"] as? JsonPrimitive)?.contentOrNull == "system"
        }
        val recovered = buildList {
            addAll(leadingSystem)
            if (lastUserIndex >= leadingSystem.size) add(history[lastUserIndex])
        }
        if (recovered == history) return null
        eventLog().append("subagent/history-recovery", buildJsonObject {
            put("agent_id", subagentId); put("step", step); put("reason", modelError.code)
            put("messages_before", history.size); put("messages_after", recovered.size)
        })
        return recovered
    }

    fun recoverOverflow(
        history: List<JsonObject>,
        durableHistory: MutableList<JsonObject>?,
        subagentId: String,
        step: Int,
        model: String,
        round: Int,
        error: Throwable,
        allowed: Boolean,
    ): List<JsonObject>? {
        if (!allowed || !contextWindowExceeded(error)) return null
        val compacted = historyCompactor.compactForOverflow(
            history,
            LocalHistorySummaryMode.WORK,
        ) ?: return null
        if (
            compacted.estimatedTokensAfter >= compacted.estimatedTokensBefore ||
            compacted.messages == history
        ) return null

        durableHistory?.let { durable ->
            applyOverflowCompaction(
                history = durable,
                compactor = historyCompactor,
                summaryMode = LocalHistorySummaryMode.WORK,
            )?.let { durableCompaction ->
                eventLog().append("subagent/compaction", buildJsonObject {
                    put("agent_id", subagentId); put("trigger", "context-overflow"); put("round", round)
                    put("omitted_messages", durableCompaction.omittedMessages)
                    put("summary", durableCompaction.summary)
                    put("estimated_tokens_before", durableCompaction.estimatedTokensBefore)
                    put("estimated_tokens_after", durableCompaction.estimatedTokensAfter)
                })
            }
        }
        eventLog().append("subagent/context-overflow-recovery", buildJsonObject {
            put("agent_id", subagentId); put("step", step); put("round", round); put("model", model)
            put("estimated_tokens_before", compacted.estimatedTokensBefore)
            put("estimated_tokens_after", compacted.estimatedTokensAfter)
            put("omitted_messages", compacted.omittedMessages)
        })
        return compacted.messages
    }
}
