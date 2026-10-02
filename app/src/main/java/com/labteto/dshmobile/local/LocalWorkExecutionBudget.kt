package com.labteto.dshmobile.local

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Work-run-local admission budget.
 *
 * estimated exposure is diagnostic/admission-only and must never be added to API-reported token
 * accounting. A reservation is committed even when a request fails because the provider may have
 * admitted work without returning usage.
 */
internal class LocalWorkExecutionBudget(
    private val exposureLimitTokens: Long = DEFAULT_EXPOSURE_LIMIT_TOKENS,
    private val pendingLimitTokens: Long = DEFAULT_PENDING_LIMIT_TOKENS,
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
) {
    private var committedExposureTokens: Long = 0L
    private var pendingExposureTokens: Long = 0L
    private var admittedRequests: Int = 0

    suspend fun reserve(estimatedInputTokens: Int): Lease {
        val estimate = estimatedInputTokens.coerceAtLeast(1).toLong()
        while (true) {
            val admitted = synchronized(this) {
                if (admittedRequests >= maxRequests) {
                    throw budgetExceeded("模型请求次数已达到 $maxRequests 次")
                }
                if (committedExposureTokens + pendingExposureTokens + estimate > exposureLimitTokens) {
                    throw budgetExceeded("本轮预计模型输入已达到安全上限")
                }
                if (pendingExposureTokens == 0L || pendingExposureTokens + estimate <= pendingLimitTokens) {
                    pendingExposureTokens += estimate
                    admittedRequests += 1
                    true
                } else {
                    false
                }
            }
            if (admitted) return Lease(this, estimate)
            delay(PENDING_RECHECK_MILLIS)
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        committedExposureTokens = committedExposureTokens,
        pendingExposureTokens = pendingExposureTokens,
        exposureLimitTokens = exposureLimitTokens,
        admittedRequests = admittedRequests,
        maxRequests = maxRequests,
    )

    @Synchronized
    private fun settle(estimate: Long, reportedInputTokens: Long?) {
        pendingExposureTokens = (pendingExposureTokens - estimate).coerceAtLeast(0L)
        val committed = reportedInputTokens?.takeIf { it > 0L } ?: estimate
        committedExposureTokens = (committedExposureTokens + committed).coerceAtMost(Long.MAX_VALUE)
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

        fun settle(reportedInputTokens: Long? = null) {
            if (settled.compareAndSet(false, true)) owner.settle(estimate, reportedInputTokens)
        }
    }

    data class Snapshot(
        val committedExposureTokens: Long,
        val pendingExposureTokens: Long,
        val exposureLimitTokens: Long,
        val admittedRequests: Int,
        val maxRequests: Int,
    )

    private companion object {
        const val DEFAULT_EXPOSURE_LIMIT_TOKENS = 1_000_000L
        const val DEFAULT_PENDING_LIMIT_TOKENS = 256_000L
        const val DEFAULT_MAX_REQUESTS = 64
        const val PENDING_RECHECK_MILLIS = 100L
    }
}

/** Per-run route breaker. Terminal account/quota failures stop sibling workers from hitting the same wall. */
internal class LocalModelRouteCircuitBreaker {
    private val openRoutes = linkedMapOf<String, String>()

    @Synchronized
    fun requireClosed(fingerprint: String) {
        val reason = openRoutes[fingerprint] ?: return
        throw LocalModelException(
            code = "MODEL_ROUTE_CIRCUIT_OPEN",
            message = "当前模型路由已停止继续请求：$reason",
            retryable = false,
        )
    }

    @Synchronized
    fun observeFailure(fingerprint: String, error: LocalModelException) {
        if (isTerminalRouteFailure(error.code)) {
            openRoutes.putIfAbsent(fingerprint, error.code)
        }
    }

    @Synchronized
    fun isOpen(fingerprint: String): Boolean = fingerprint in openRoutes
}

internal fun isTerminalRouteFailure(code: String): Boolean =
    code in setOf(
        "CHATGPT_PLAN_LIMIT_REACHED",
        "MODEL_ROUTE_CIRCUIT_OPEN",
        "WORK_BUDGET_EXHAUSTED",
        "MODEL_AUTH_FAILED",
        "MODEL_CREDENTIAL_MISSING",
        "NO_MODEL_CREDENTIAL",
    )


internal fun shouldAutoContinueWorkFailure(
    errorCode: String?,
    automaticContinuationCount: Int,
    pendingInputs: Int,
): Boolean =
    errorCode == "CHATGPT_PLAN_STREAM_INTERRUPTED" &&
        automaticContinuationCount == 0 &&
        pendingInputs == 0
