package com.labteto.dshmobile.local

import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.modelFailureKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Work-run-local model exposure budget.
 *
 * Reservations are provisional until the transport can classify the attempt. Only requests that
 * may actually have reached provider inference become committed exposure. Explicit HTTP rejection
 * and failures proven to occur before the full request body is sent release both exposure and the
 * request slot. Estimated exposure is safety-only and never enters user token accounting.
 */
internal class LocalWorkExecutionBudget(
    private val exposureLimitTokens: Long = DEFAULT_EXPOSURE_LIMIT_TOKENS,
    private val pendingLimitTokens: Long = DEFAULT_PENDING_LIMIT_TOKENS,
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
) {
    private var committedExposureTokens: Long = 0L
    private var pendingExposureTokens: Long = 0L
    private var reservedRequests: Int = 0
    private var admittedRequests: Int = 0

    suspend fun reserve(estimatedInputTokens: Int): Lease {
        val estimate = estimatedInputTokens.coerceAtLeast(1).toLong()
        while (true) {
            val reserved = synchronized(this) {
                if (admittedRequests + reservedRequests >= maxRequests) {
                    throw budgetExceeded("模型请求次数已达到 $maxRequests 次")
                }
                if (committedExposureTokens + pendingExposureTokens + estimate > exposureLimitTokens) {
                    throw budgetExceeded("本轮预计模型输入已达到安全上限")
                }
                if (pendingExposureTokens == 0L || pendingExposureTokens + estimate <= pendingLimitTokens) {
                    pendingExposureTokens += estimate
                    reservedRequests += 1
                    true
                } else {
                    false
                }
            }
            if (reserved) return Lease(this, estimate)
            delay(PENDING_RECHECK_MILLIS)
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        committedExposureTokens = committedExposureTokens,
        pendingExposureTokens = pendingExposureTokens,
        exposureLimitTokens = exposureLimitTokens,
        admittedRequests = admittedRequests,
        reservedRequests = reservedRequests,
        maxRequests = maxRequests,
    )

    @Synchronized
    private fun commit(estimate: Long, reportedInputTokens: Long?) {
        pendingExposureTokens = (pendingExposureTokens - estimate).coerceAtLeast(0L)
        reservedRequests = (reservedRequests - 1).coerceAtLeast(0)
        admittedRequests += 1
        val committed = reportedInputTokens?.takeIf { it > 0L } ?: estimate
        committedExposureTokens = (committedExposureTokens + committed).coerceAtMost(Long.MAX_VALUE)
    }

    @Synchronized
    private fun release(estimate: Long) {
        pendingExposureTokens = (pendingExposureTokens - estimate).coerceAtLeast(0L)
        reservedRequests = (reservedRequests - 1).coerceAtLeast(0)
    }

    private fun budgetExceeded(detail: String) = LocalModelException(
        code = "WORK_BUDGET_EXHAUSTED",
        message = "$detail；已有进度会保留，请缩小任务范围或开始新的工作回合",
        retryable = false,
    )

    class Lease internal constructor(
        private val owner: LocalWorkExecutionBudget,
        private val estimate: Long,
    ) {
        private val settled = AtomicBoolean(false)

        fun commit(reportedInputTokens: Long? = null) {
            if (settled.compareAndSet(false, true)) owner.commit(estimate, reportedInputTokens)
        }

        fun release() {
            if (settled.compareAndSet(false, true)) owner.release(estimate)
        }
    }

    data class Snapshot(
        val committedExposureTokens: Long,
        val pendingExposureTokens: Long,
        val exposureLimitTokens: Long,
        val admittedRequests: Int,
        val reservedRequests: Int,
        val maxRequests: Int,
    )

    private companion object {
        const val DEFAULT_EXPOSURE_LIMIT_TOKENS = 1_000_000L
        const val DEFAULT_PENDING_LIMIT_TOKENS = 256_000L
        const val DEFAULT_MAX_REQUESTS = 64
        const val PENDING_RECHECK_MILLIS = 100L
    }
}

/**
 * Two-tier route breaker.
 *
 * - Durable account/credential faults are per-run: they stop sibling workers of the same run.
 * - Transient transport faults are route-scoped and outlive one work run, because an unreachable
 *   route is otherwise replayed once per automatic continuation and every replay re-sends the whole
 *   conversation prefix. One measured session produced ten identical
 *   `CHATGPT_PLAN_STREAM_INTERRUPTED` attempts on a single profile, burning roughly 361k estimated
 *   input tokens before the user intervened manually.
 */
internal class LocalModelRouteCircuitBreaker {
    private val openRoutes = linkedMapOf<String, String>()

    @Synchronized
    fun requireClosed(fingerprint: String) {
        val reason = openRoutes[fingerprint]
        if (reason != null) {
            throw LocalModelException(
                code = "MODEL_ROUTE_CIRCUIT_OPEN",
                message = "当前模型路由已停止继续请求：$reason",
                retryable = false,
            )
        }
        val cooldown = LocalModelRouteHealth.activeCooldown(fingerprint) ?: return
        throw LocalModelException(
            code = "MODEL_ROUTE_CIRCUIT_COOLDOWN",
            message = "当前模型路由连续 ${cooldown.failureStreak} 次传输失败（${cooldown.reason}），" +
                "已暂停 ${cooldown.millis / 1_000} 秒，避免继续重放同一上下文；可切换到别亳模型档案后继续。",
            retryable = false,
        )
    }

    @Synchronized
    fun observeFailure(fingerprint: String, error: LocalModelException) {
        if (isTerminalRouteFailure(error)) {
            openRoutes.putIfAbsent(fingerprint, error.code)
            LocalModelRouteHealth.recordSuccess(fingerprint)
            return
        }
        LocalModelRouteHealth.recordFailure(fingerprint, modelFailureKind(error))
    }

    @Synchronized
    fun observeSuccess(fingerprint: String) {
        LocalModelRouteHealth.recordSuccess(fingerprint)
    }

    @Synchronized
    fun isOpen(fingerprint: String): Boolean =
        fingerprint in openRoutes || LocalModelRouteHealth.activeCooldown(fingerprint) != null
}

internal data class LocalRouteCooldown(
    val reason: String,
    val failureStreak: Int,
    val millis: Long,
    val until: Long,
)

/**
 * Route health belongs to the provider route, not to one work run, so the transient-failure streak
 * is process-wide: a route that keeps dropping streams must not be replayed once per continuation
 * turn with a freshly reset counter. Durable account faults stay per-run and are not registered
 * here, because they are already answered by the durable tier.
 */
internal object LocalModelRouteHealth {
    private val failures = linkedMapOf<String, Int>()
    private val cooldowns = linkedMapOf<String, LocalRouteCooldown>()
    private var clock: () -> Long = System::currentTimeMillis

    @Synchronized
    fun recordFailure(fingerprint: String, failureKind: String) {
        if (failureKind !in TRANSIENT_ROUTE_FAILURE_KINDS) return
        val streak = (failures[fingerprint] ?: 0) + 1
        failures[fingerprint] = streak
        if (streak < TRANSIENT_FAILURE_THRESHOLD) return
        val previous = cooldowns[fingerprint]?.millis ?: 0L
        val millis = if (previous == 0L) {
            TRANSIENT_COOLDOWN_MILLIS
        } else {
            (previous * 2).coerceAtMost(MAX_TRANSIENT_COOLDOWN_MILLIS)
        }
        cooldowns[fingerprint] = LocalRouteCooldown(
            reason = failureKind,
            failureStreak = streak,
            millis = millis,
            until = clock() + millis,
        )
    }

    @Synchronized
    fun recordSuccess(fingerprint: String) {
        failures.remove(fingerprint)
        cooldowns.remove(fingerprint)
    }

    @Synchronized
    fun activeCooldown(fingerprint: String): LocalRouteCooldown? {
        val cooldown = cooldowns[fingerprint] ?: return null
        return if (cooldown.until > clock()) cooldown else null
    }

    @Synchronized
    fun consecutiveFailures(fingerprint: String): Int = failures[fingerprint] ?: 0

    @Synchronized
    internal fun resetForTest(replacementClock: (() -> Long)? = null) {
        failures.clear()
        cooldowns.clear()
        clock = replacementClock ?: System::currentTimeMillis
    }
}

private val TRANSIENT_ROUTE_FAILURE_KINDS = setOf(
    "stream_interrupted",
    "request_maybe_admitted",
    "network_failure",
    "connection_reset",
    "unexpected_eof",
    "timeout",
    "dns_failure",
    "connect_failed",
    "tls_failure",
    "provider_failure",
)

private const val TRANSIENT_FAILURE_THRESHOLD = 3
private const val TRANSIENT_COOLDOWN_MILLIS = 90_000L
private const val MAX_TRANSIENT_COOLDOWN_MILLIS = 900_000L

private fun isTerminalRouteFailure(error: LocalModelException): Boolean =
    modelFailureKind(error) in setOf(
        "credential_missing",
        "auth_failed",
        "auth_forbidden",
        "account_limit",
    )

/**
 * Code-only form is kept for workflow-level delegated errors that no longer carry the original
 * exception. Local work budget/timeout errors are intentionally not route failures.
 */
internal fun isTerminalRouteFailure(code: String): Boolean =
    code in setOf(
        "CHATGPT_PLAN_LIMIT_REACHED",
        "CHATGPT_PLAN_USER_NOT_ELIGIBLE",
        "CHATGPT_PLAN_INVALID_USER",
        "CHATGPT_PLAN_PERMISSION_CONTEXT_INVALID",
        "MODEL_CREDENTIAL_MISSING",
        "NO_MODEL_CREDENTIAL",
        "MODEL_HTTP_401",
        "MODEL_HTTP_402",
        "MODEL_HTTP_403",
    )

internal fun shouldAutoContinueWorkFailure(
    error: LocalModelException?,
    automaticContinuationCount: Int,
    pendingInputs: Int,
): Boolean =
    error?.continuationEligible == true &&
        automaticContinuationCount < MAX_AUTOMATIC_CONTINUATIONS &&
        pendingInputs == 0

private const val MAX_AUTOMATIC_CONTINUATIONS = 2

internal class LocalWorkExecutionControl(
    val budget: LocalWorkExecutionBudget = LocalWorkExecutionBudget(),
    val circuitBreaker: LocalModelRouteCircuitBreaker = LocalModelRouteCircuitBreaker(),
)

internal suspend fun executeWithModelAdmission(
    control: LocalWorkExecutionControl?,
    profileId: String,
    model: String,
    baseUrl: String,
    contextWindowTokensOverride: Int? = null,
    messages: List<kotlinx.serialization.json.JsonObject>,
    tools: kotlinx.serialization.json.JsonArray,
    block: suspend () -> LocalModelReply,
): LocalModelReply {
    val pressure = LocalPromptPressureMeter.measure(
        messages = messages,
        tools = tools,
        operationalLimitTokens = operationalInputLimitTokens(model, baseUrl, contextWindowTokensOverride),
        modelContextWindowTokens = documentedContextWindowTokens(model, baseUrl, contextWindowTokensOverride),
    )
    if (pressure.estimatedInputTokens > pressure.operationalLimitTokens) {
        throw LocalModelException(
            code = "MODEL_CONTEXT_BUDGET_EXCEEDED",
            message = "预计输入 ${pressure.estimatedInputTokens} token，超过当前路由安全上限 ${pressure.operationalLimitTokens}",
            retryable = false,
        )
    }
    control?.circuitBreaker?.requireClosed(profileId)
    val lease = control?.budget?.reserve(pressure.estimatedInputTokens)
    try {
        val reply = block()
        control?.circuitBreaker?.observeSuccess(profileId)
        lease?.commit(reply.usage.promptTokens.takeIf { reply.usage.reported })
        return reply
    } catch (cancelled: CancellationException) {
        lease?.commit()
        throw cancelled
    } catch (error: LocalModelException) {
        when (error.admissionState) {
            LocalModelAdmissionState.NOT_SENT,
            LocalModelAdmissionState.REJECTED -> lease?.release()
            else -> lease?.commit()
        }
        control?.circuitBreaker?.observeFailure(profileId, error)
        throw error
    } catch (error: Throwable) {
        lease?.commit()
        throw error
    }
}
