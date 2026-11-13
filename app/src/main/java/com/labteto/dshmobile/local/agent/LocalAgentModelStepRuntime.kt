package com.labteto.dshmobile.local.agent

import com.labteto.dshmobile.harness.agent.AgentRequestEventSink
import com.labteto.dshmobile.harness.agent.AgentRequestExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

internal data class LocalAgentModelStepRecovery(
    val messages: List<JsonObject>,
    val reason: String,
)

internal fun interface LocalAgentModelStepRecoveryPolicy {
    suspend fun recover(
        error: Throwable,
        activeMessages: List<JsonObject>,
        completedRecoveries: Int,
    ): LocalAgentModelStepRecovery?
}

/**
 * Shared model-step lifecycle for foreground agents, subagents and bounded auxiliary model work.
 *
 * Domain owners still decide what recovery means. This runtime owns bounded retries, cancellation
 * propagation and monotonic recovery to a different message set.
 */
internal class LocalAgentModelStepRuntime {
    fun requestExecutor(
        maxAttempts: Int,
        retryable: (Exception) -> Boolean,
        backoffMillis: (failedAttempt: Int, error: Exception) -> Long = { failedAttempt, _ ->
            1_000L shl (failedAttempt - 1).coerceIn(0, 20)
        },
        eventSink: AgentRequestEventSink = AgentRequestEventSink { },
    ): AgentRequestExecutor = AgentRequestExecutor(
        maxAttempts = maxAttempts,
        retryable = retryable,
        eventSink = eventSink,
        backoffMillis = backoffMillis,
    )

    suspend fun <T> recover(
        initialMessages: List<JsonObject>,
        recoveryPolicy: LocalAgentModelStepRecoveryPolicy?,
        block: suspend (activeMessages: List<JsonObject>) -> T,
    ): T {
        var activeMessages = initialMessages
        var completedRecoveries = 0
        while (true) {
            try {
                return block(activeMessages)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovery = recoveryPolicy
                    ?.recover(error, activeMessages, completedRecoveries)
                    ?: throw error
                if (recovery.messages == activeMessages) throw error
                activeMessages = recovery.messages
                completedRecoveries += 1
            }
        }
    }

    suspend fun <T> execute(
        initialMessages: List<JsonObject>,
        maxAttempts: Int,
        retryable: (Exception) -> Boolean,
        backoffMillis: (failedAttempt: Int, error: Exception) -> Long = { failedAttempt, _ ->
            1_000L shl (failedAttempt - 1).coerceIn(0, 20)
        },
        eventSink: AgentRequestEventSink = AgentRequestEventSink { },
        recoveryPolicy: LocalAgentModelStepRecoveryPolicy? = null,
        block: suspend (activeMessages: List<JsonObject>) -> T,
    ): T {
        val executor = requestExecutor(
            maxAttempts = maxAttempts,
            retryable = retryable,
            backoffMillis = backoffMillis,
            eventSink = eventSink,
        )
        return recover(initialMessages, recoveryPolicy) { activeMessages ->
            executor.execute { block(activeMessages) }
        }
    }
}
