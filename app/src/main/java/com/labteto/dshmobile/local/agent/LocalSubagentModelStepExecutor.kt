package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.resource.HarnessResourceScheduler
import com.labteto.dshmobile.local.LocalAgentModelRequestRuntime
import com.labteto.dshmobile.local.LocalHistoryCompactor
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.LocalModelReply
import com.labteto.dshmobile.local.LocalRunModelSurface
import com.labteto.dshmobile.local.LocalSessionEventLog
import com.labteto.dshmobile.local.LocalWorkExecutionControl
import com.labteto.dshmobile.local.model.LocalModelGateway
import com.labteto.dshmobile.local.model.LocalModelHistoryBuffer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Owns subagent model-step orchestration; recovery details live in LocalSubagentModelStepPolicy. */
internal class LocalSubagentModelStepExecutor(
    modelGateway: LocalModelGateway,
    private val modelAttempts: () -> Int,
    resourceScheduler: HarnessResourceScheduler,
    eventLog: () -> LocalSessionEventLog,
    historyCompactor: LocalHistoryCompactor,
    executionControl: LocalWorkExecutionControl? = null,
) {
    private val requestBoundary = LocalSubagentModelRequestBoundary(
        LocalAgentModelRequestRuntime(modelGateway, resourceScheduler),
        eventLog,
        executionControl,
    )
    private val modelStepRuntime = LocalAgentModelStepRuntime()
    private val stepPolicy = LocalSubagentModelStepPolicy(eventLog, historyCompactor)

    suspend fun complete(
        surface: LocalRunModelSurface,
        history: List<JsonObject>,
        tools: JsonArray,
        subagentId: String,
        step: Int,
        durableHistory: LocalModelHistoryBuffer? = null,
        allowContextOverflowRecovery: Boolean = true,
    ): LocalModelReply = modelStepRuntime.execute(
        initialMessages = history,
        maxAttempts = modelAttempts().coerceIn(1, 5),
        retryable = { error ->
            (error as? LocalModelException)?.retryable == true || error is java.io.IOException
        },
        backoffMillis = { failedAttempt, error ->
            (error as? LocalModelException)?.providerRetryAfterMs?.coerceIn(0L, 60_000L)
                ?: (1_000L shl (failedAttempt - 1).coerceIn(0, 20))
        },
        eventSink = stepPolicy.eventSink(subagentId, step),
        recoveryPolicy = stepPolicy.recoveryPolicy(
            surface = surface,
            subagentId = subagentId,
            step = step,
            durableHistory = durableHistory,
            allowContextOverflowRecovery = allowContextOverflowRecovery,
        ),
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
