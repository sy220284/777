package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.*
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.*
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.compactOverflow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns subagent-specific recovery policy while the shared runtime owns request-step lifecycle. */
internal class LocalSubagentModelStepExecutor(
    private val modelGateway: LocalModelGateway,
    private val modelAttempts: () -> Int,
    private val resourceScheduler: HarnessResourceScheduler,
    private val eventLog: () -> LocalSessionEventLog,
    private val historyCompactor: LocalHistoryCompactor,
    private val executionControl: LocalWorkExecutionControl? = null,
) {
    private val requestBoundary = LocalSubagentModelRequestBoundary(
        LocalAgentModelRequestRuntime(modelGateway, resourceScheduler), eventLog, executionControl,
    )
    private val modelStepRuntime = LocalAgentModelStepRuntime()

    suspend fun complete(
        surface: LocalRunModelSurface,
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
        durableHistory: LocalModelHistoryBuffer? = null,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply {
        var overflowRound = 0
        var structureRecoveryAttempted = false
        var continuationRound = 0
        return modelStepRuntime.execute(
            initialMessages = history,
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
            recoveryPolicy = LocalAgentModelStepRecoveryPolicy { error, activeHistory, _ ->
                val modelError = error as? LocalModelException
                val continuation = modelError?.let {
                    subagentContinuationMessage(it, continuationRound)
                }
                if (continuation != null) {
                    continuationRound += 1
                    recordSubagentContinuation(
                        continuation,
                        modelError,
                        continuationRound,
                        durableHistory,
                        eventLog(),
                        subagentId,
                        step,
                    )
                    LocalAgentModelStepRecovery(
                        messages = activeHistory + continuation,
                        reason = "continuation",
                    )
                } else {
                    val structureRecovery = if (!structureRecoveryAttempted) {
                        LocalSubagentStructureRecovery.recover(
                            error,
                            activeHistory,
                            subagentId,
                            step,
                            eventLog(),
                        )
                    } else {
                        null
                    }
                    if (structureRecovery != null) {
                        structureRecoveryAttempted = true
                        LocalAgentModelStepRecovery(
                            messages = structureRecovery,
                            reason = "structure",
                        )
                    } else if (allowContextOverflowRecovery && contextWindowExceeded(error)) {
                        val compacted = historyCompactor.compactForOverflow(
                            activeHistory,
                            LocalHistorySummaryMode.WORK,
                        )
                        val madeProgress = compacted != null &&
                            compacted.estimatedTokensAfter < compacted.estimatedTokensBefore &&
                            compacted.messages != activeHistory
                        if (!madeProgress || compacted == null) {
                            null
                        } else {
                            overflowRound += 1
                            durableHistory?.compactOverflow(
                                compactor = historyCompactor,
                                summaryMode = LocalHistorySummaryMode.WORK,
                            )?.let { durableCompaction ->
                                recordSubagentCompaction(
                                    durableCompaction,
                                    eventLog(),
                                    subagentId,
                                    overflowRound,
                                )
                            }
                            eventLog().append("subagent/context-overflow-recovery", buildJsonObject {
                                put("agent_id", subagentId)
                                put("step", step)
                                put("round", overflowRound)
                                put("model", surface.model)
                                put("estimated_tokens_before", compacted.estimatedTokensBefore)
                                put("estimated_tokens_after", compacted.estimatedTokensAfter)
                                put("omitted_messages", compacted.omittedMessages)
                            })
                            LocalAgentModelStepRecovery(
                                messages = compacted.messages,
                                reason = "context_overflow",
                            )
                        }
                    } else {
                        null
                    }
                }
            },
        ) { activeHistory ->
            requestBoundary.complete(
                surface = surface,
                messages = activeHistory,
                tools = tools,
                subagentId = subagentId,
                step = step,
            )
        }
    }
}
