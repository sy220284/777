package com.labteto.dshmobile.harness.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

sealed interface AgentRequestEvent {
    val attempt: Int

    data class AttemptStarted(
        override val attempt: Int,
        val maxAttempts: Int,
    ) : AgentRequestEvent

    data class AttemptFailed(
        override val attempt: Int,
        val maxAttempts: Int,
        val retryable: Boolean,
        val willRetry: Boolean,
        val reason: String,
    ) : AgentRequestEvent

    data class RetryScheduled(
        override val attempt: Int,
        val nextAttempt: Int,
        val delayMillis: Long,
    ) : AgentRequestEvent

    data class AttemptSucceeded(
        override val attempt: Int,
    ) : AgentRequestEvent

    data class AttemptCancelled(
        override val attempt: Int,
        val reason: String?,
    ) : AgentRequestEvent
}

fun interface AgentRequestEventSink {
    suspend fun append(event: AgentRequestEvent)
}

/**
 * Platform-neutral model-request retry policy shared by parent agents and subagents.
 *
 * Cancellation is never converted into a retry. Every failed attempt is observable before a retry
 * is scheduled, which keeps durable diagnostics and UI state in agreement with the actual request
 * lifecycle.
 */
class AgentRequestExecutor(
    maxAttempts: Int,
    private val retryable: (Exception) -> Boolean,
    private val eventSink: AgentRequestEventSink = AgentRequestEventSink { },
    private val providerRetryDelayMillis: (error: Exception, failedAttempt: Int) -> Long? = { _, _ -> null },
    private val backoffMillis: (failedAttempt: Int) -> Long = { failedAttempt ->
        1_000L shl (failedAttempt - 1).coerceIn(0, 20)
    },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val maxAttempts = maxAttempts.coerceIn(1, MAX_ATTEMPTS)

    suspend fun <T> execute(block: suspend (attempt: Int) -> T): T {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            eventSink.append(AgentRequestEvent.AttemptStarted(attempt, maxAttempts))
            try {
                val result = block(attempt)
                eventSink.append(AgentRequestEvent.AttemptSucceeded(attempt))
                return result
            } catch (cancelled: CancellationException) {
                eventSink.append(
                    AgentRequestEvent.AttemptCancelled(
                        attempt = attempt,
                        reason = cancelled.message,
                    ),
                )
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                val isRetryable = retryable(error)
                val willRetry = isRetryable && attempt < maxAttempts
                eventSink.append(
                    AgentRequestEvent.AttemptFailed(
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        retryable = isRetryable,
                        willRetry = willRetry,
                        reason = error.message ?: error::class.java.simpleName,
                    ),
                )
                if (!willRetry) throw error
                val delayMillis = (
                    providerRetryDelayMillis(error, attempt)
                        ?: backoffMillis(attempt)
                ).coerceIn(0L, MAX_RETRY_DELAY_MILLIS)
                eventSink.append(
                    AgentRequestEvent.RetryScheduled(
                        attempt = attempt,
                        nextAttempt = attempt + 1,
                        delayMillis = delayMillis,
                    ),
                )
                if (delayMillis > 0L) sleeper(delayMillis)
            }
        }
        throw lastError ?: IllegalStateException("模型请求失败")
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
        const val MAX_RETRY_DELAY_MILLIS = 120_000L
    }
}
