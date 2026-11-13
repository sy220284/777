package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.contextWindowExceeded
import com.labteto.dshmobile.local.model.LocalHistoryCompactor
import com.labteto.dshmobile.local.model.LocalHistorySummaryMode
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import com.labteto.dshmobile.local.model.LocalRunModelSurface
import com.labteto.dshmobile.local.model.compactOverflow
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Subagent-specific request diagnostics and recovery semantics. */
internal class LocalSubagentModelStepPolicy(
    private val eventLog: () -> LocalSessionEventLog,
    private val historyCompactor: LocalHistoryCompactor,
) {
    fun eventSink(subagentId: String, step: Int) = AgentRequestEventSink { event ->
        when (event) {
            is AgentRequestEvent.AttemptStarted -> eventLog().append("subagent/request", buildJsonObject {
                put("agent_id", subagentId)
                put("step", step)
                put("attempt", event.attempt)
                put("max_attempts", event.maxAttempts)
            })
            is AgentRequestEvent.AttemptFailed -> eventLog().append("subagent/request-error", buildJsonObject {
                put("agent_id", subagentId)
                put("step", step)
                put("attempt", event.attempt)
                put("retryable", event.retryable)
                put("will_retry", event.willRetry)
                put("detail", event.reason.take(2_000))
            })
            is AgentRequestEvent.RetryScheduled -> eventLog().append("subagent/retry", buildJsonObject {
                put("agent_id", subagentId)
                put("step", step)
                put("attempt", event.attempt)
                put("next_attempt", event.nextAttempt)
                put("delay_ms", event.delayMillis)
            })
            is AgentRequestEvent.AttemptCancelled -> eventLog().append("subagent/request-cancelled", buildJsonObject {
                put("agent_id", subagentId)
                put("step", step)
                put("attempt", event.attempt)
                event.reason?.let { put("detail", it.take(2_000)) }
            })
            is AgentRequestEvent.AttemptSucceeded -> Unit
        }
    }

    fun recoveryPolicy(
        surface: LocalRunModelSurface,
        subagentId: String,
        step: Int,
        durableHistory: LocalModelHistoryBuffer?,
        allowContextOverflowRecovery: Boolean,
    ): LocalAgentModelStepRecoveryPolicy {
        var overflowRound = 0
        var structureRecoveryAttempted = false
        var continuationRound = 0
        return LocalAgentModelStepRecoveryPolicy { error, activeHistory, _ ->
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
                when {
                    structureRecovery != null -> {
                        structureRecoveryAttempted = true
                        LocalAgentModelStepRecovery(
                            messages = structureRecovery,
                            reason = "structure",
                        )
                    }
                    allowContextOverflowRecovery && contextWindowExceeded(error) -> {
                        recoverOverflow(
                            surface = surface,
                            activeHistory = activeHistory,
                            subagentId = subagentId,
                            step = step,
                            durableHistory = durableHistory,
                            nextRound = overflowRound + 1,
                        )?.also { overflowRound += 1 }
                    }
                    else -> null
                }
            }
        }
    }

    private fun recoverOverflow(
        surface: LocalRunModelSurface,
        activeHistory: List<kotlinx.serialization.json.JsonObject>,
        subagentId: String,
        step: Int,
        durableHistory: LocalModelHistoryBuffer?,
        nextRound: Int,
    ): LocalAgentModelStepRecovery? {
        val compacted = historyCompactor.compactForOverflow(
            activeHistory,
            LocalHistorySummaryMode.WORK,
        ) ?: return null
        if (
            compacted.estimatedTokensAfter >= compacted.estimatedTokensBefore ||
            compacted.messages == activeHistory
        ) return null

        durableHistory?.compactOverflow(
            compactor = historyCompactor,
            summaryMode = LocalHistorySummaryMode.WORK,
        )?.let { durableCompaction ->
            recordSubagentCompaction(durableCompaction, eventLog(), subagentId, nextRound)
        }
        eventLog().append("subagent/context-overflow-recovery", buildJsonObject {
            put("agent_id", subagentId)
            put("step", step)
            put("round", nextRound)
            put("model", surface.model)
            put("estimated_tokens_before", compacted.estimatedTokensBefore)
            put("estimated_tokens_after", compacted.estimatedTokensAfter)
            put("omitted_messages", compacted.omittedMessages)
        })
        return LocalAgentModelStepRecovery(
            messages = compacted.messages,
            reason = "context_overflow",
        )
    }
}
