package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.harness.agent.AgentRequestEvent
import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.local.LocalHarnessState
import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.agent.LocalAgentModelStepRuntime
import com.labteto.dshmobile.local.runtime.LocalRuntimeStateStore
import com.labteto.dshmobile.local.session.LocalSessionEventLog
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared bounded no-tool model request boundary for auxiliary product capabilities.
 *
 * It preserves the same frozen route, shared model-request resource, admission and retry policy as
 * foreground model steps without forcing feature code through the Runtime Kernel.
 */
@Singleton
internal class LocalAuxiliaryModelRequestRuntime @Inject constructor(
    modelGateway: LocalModelGateway,
    runtimeStateStore: LocalRuntimeStateStore,
) {
    private val requestRuntime =
        LocalAgentModelRequestRuntime(modelGateway, runtimeStateStore.resourceScheduler)
    private val stepRuntime = LocalAgentModelStepRuntime()

    internal suspend fun complete(
        snapshot: LocalHarnessState,
        profile: LocalModelProfile,
        messages: List<JsonObject>,
        eventLog: LocalSessionEventLog,
        operation: String,
        temperature: Double? = null,
    ): LocalModelReply {
        val surface = profile.toRunModelSurface()
        return stepRuntime.execute(
            initialMessages = messages,
            maxAttempts = snapshot.modelState.modelAttempts.coerceIn(1, 5),
            retryable = { error ->
                (error as? LocalModelException)?.retryable == true || error is IOException
            },
            backoffMillis = { failedAttempt, error ->
                (error as? LocalModelException)?.providerRetryAfterMs
                    ?.coerceIn(0L, 60_000L)
                    ?: (1_000L shl (failedAttempt - 1).coerceIn(0, 20))
            },
            eventSink = AgentRequestEventSink { event ->
                when (event) {
                    is AgentRequestEvent.AttemptFailed -> eventLog.append(
                        "auxiliary-model/attempt",
                        buildJsonObject {
                            put("operation", operation)
                            put("attempt", event.attempt)
                            put("status", "failed")
                            put("retryable", event.retryable)
                            put("will_retry", event.willRetry)
                            put("detail", event.reason.take(2_000))
                        },
                    )
                    is AgentRequestEvent.RetryScheduled -> eventLog.append(
                        "auxiliary-model/retry",
                        buildJsonObject {
                            put("operation", operation)
                            put("attempt", event.attempt)
                            put("next_attempt", event.nextAttempt)
                            put("delay_ms", event.delayMillis)
                        },
                    )
                    is AgentRequestEvent.AttemptCancelled -> eventLog.append(
                        "auxiliary-model/attempt",
                        buildJsonObject {
                            put("operation", operation)
                            put("attempt", event.attempt)
                            put("status", "cancelled")
                            event.reason?.let { put("detail", it.take(2_000)) }
                        },
                    )
                    is AgentRequestEvent.AttemptStarted,
                    is AgentRequestEvent.AttemptSucceeded,
                    -> Unit
                }
            },
        ) { activeMessages ->
            requestRuntime.complete(
                surface = surface,
                messages = activeMessages,
                tools = JsonArray(emptyList()),
                streaming = false,
                temperature = temperature,
            )
        }
    }
}
