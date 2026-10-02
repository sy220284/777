package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns one subagent model-step request, retry and context-overflow recovery policy. */
internal class LocalSubagentModelStepExecutor(
    private val modelGateway: LocalModelGateway,
    private val modelAttempts: () -> Int,
    private val resourceScheduler: HarnessResourceScheduler,
    private val eventLog: () -> LocalSessionEventLog,
    private val historyCompactor: LocalHistoryCompactor,
    private val executionControl: LocalWorkExecutionControl? = null,
) {
    private val requestBoundary = LocalSubagentModelRequestBoundary(
        modelGateway, resourceScheduler, eventLog, executionControl,
    )

    suspend fun complete(
        profile: LocalModelProfile,
        baseUrl: String,
        model: String,
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
        durableHistory: LocalModelHistoryBuffer? = null,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply {
        val executor = AgentRequestExecutor(
            maxAttempts = modelAttempts().coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is java.io.IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs?.coerceIn(0L, 60_000L)
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
                    requestBoundary.complete(
                        profile, model, baseUrl, activeHistory, tools, subagentId, step,
                    )
                }
            } catch (error: Throwable) {
                if (!structureRecoveryAttempted) {
                    LocalSubagentStructureRecovery.recover(
                        error, activeHistory, subagentId, step, eventLog(),
                    )?.let { recovered ->
                        structureRecoveryAttempted = true
                        activeHistory = recovered
                        continue
                    }
                }
                if (!allowContextOverflowRecovery || !contextWindowExceeded(error)) throw error
                val compacted = historyCompactor.compactForOverflow(
                    activeHistory,
                    LocalHistorySummaryMode.WORK,
                ) ?: throw error
                val madeProgress = compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                    compacted.messages != activeHistory
                if (!madeProgress) throw error

                overflowRound += 1
                durableHistory?.compactOverflow(
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
