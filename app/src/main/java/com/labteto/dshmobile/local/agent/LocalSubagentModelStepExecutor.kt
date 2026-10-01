package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import com.labteto.dshmobile.harness.resource.HarnessResourceKind
import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.LocalHistoryCompactor
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelProfile
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.model.LocalModelGateway
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Owns one subagent model-step request, retry and bounded recovery policy. */
internal class LocalSubagentModelStepExecutor(
    private val modelGateway: LocalModelGateway,
    private val modelAttempts: () -> Int,
    private val resourceScheduler: HarnessResourceScheduler,
    private val eventLog: () -> LocalSessionEventLog,
    historyCompactor: LocalHistoryCompactor,
) {
    private val support = LocalSubagentModelStepSupport(eventLog, historyCompactor)

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
                (error as? LocalModelException)?.retryable == true || error is IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs
                    ?.coerceIn(0L, 60_000L)
                    ?: (1_000L shl (failedAttempt - 1).coerceIn(0, 20))
            },
            eventSink = support.requestEvents(subagentId, step),
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
                            support.providerError(subagentId, step, error)
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                support.recoverStructure(
                    history = activeHistory,
                    subagentId = subagentId,
                    step = step,
                    error = error,
                    alreadyUsed = structureRecoveryUsed,
                )?.let { recovered ->
                    structureRecoveryUsed = true
                    activeHistory = recovered
                    continue
                }

                overflowRound += 1
                support.recoverOverflow(
                    history = activeHistory,
                    durableHistory = durableHistory,
                    subagentId = subagentId,
                    step = step,
                    model = model,
                    round = overflowRound,
                    error = error,
                    allowed = allowContextOverflowRecovery,
                )?.let { recovered ->
                    activeHistory = recovered
                    continue
                }
                throw error
            }
        }
    }
}
