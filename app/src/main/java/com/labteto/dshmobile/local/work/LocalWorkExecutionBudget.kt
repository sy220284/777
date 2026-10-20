package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.LocalModelException
import com.labteto.dshmobile.local.documentedContextWindowTokens
import com.labteto.dshmobile.local.model.LocalModelAdmissionState
import com.labteto.dshmobile.local.model.LocalModelCancellationException
import com.labteto.dshmobile.local.model.LocalModelReply
import com.labteto.dshmobile.local.model.LocalModelBudgetPolicy
import com.labteto.dshmobile.local.model.LocalPromptPressureMeter
import com.labteto.dshmobile.local.model.routeFingerprint
import com.labteto.dshmobile.local.operationalInputLimitTokens
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Work-run-local model admission guard.
 *
 * Reservations are provisional until the transport can classify the attempt. Only requests that
 * may actually have reached provider inference become committed exposure. Explicit HTTP rejection
 * and failures proven to occur before the full request body is sent release both exposure and the
 * request slot. Cumulative exposure is diagnostic only and never blocks a long-running Work run.
 * Estimated exposure only bounds concurrently pending requests and never enters user token accounting.
 */
internal class LocalWorkExecutionBudget(
    pendingLimitTokens: Long = DEFAULT_PENDING_LIMIT_TOKENS,
    maxRequests: Int = DEFAULT_MAX_REQUESTS,
) {
    private var pendingLimitTokens: Long = pendingLimitTokens
    private var maxRequests: Int = maxRequests
    private var reportedExposureTokens: Long = 0L
    private var uncertainExposureTokens: Long = 0L
    private var pendingExposureTokens: Long = 0L
    private var reservedRequests: Int = 0
    private var admittedRequests: Int = 0
    private var currentSlice: Long = 0L
    private var reservedInSlice: Int = 0
    private data class EstimateCalibration(
        var scale: Double = 1.0,
        var samples: Int = 0,
    )

    private val calibrations = linkedMapOf<String, EstimateCalibration>()
    private var lastCalibrationKey: String = DEFAULT_CALIBRATION_KEY

    suspend fun reserve(
        estimatedInputTokens: Int,
        calibrationKey: String = DEFAULT_CALIBRATION_KEY,
    ): Lease {
        val rawEstimate = estimatedInputTokens.coerceAtLeast(1).toLong()
        val normalizedCalibrationKey = calibrationKey.trim().takeIf(String::isNotBlank)
            ?: DEFAULT_CALIBRATION_KEY
        while (true) {
            var reservedEstimate = 0L
            var reservedSlice = 0L
            val reserved = synchronized(this) {
                // Quotas apply to the executing slice. Previous-slice children retain their
                // leases and token exposure but must not consume the new slice's slots.
                if (admittedRequests + reservedInSlice >= maxRequests) {
                    throw budgetExceeded("模型请求次数已达到 $maxRequests 次")
                }
                reservedEstimate = calibratedEstimate(rawEstimate, normalizedCalibrationKey)
                if (
                    pendingExposureTokens == 0L ||
                    saturatingAdd(pendingExposureTokens, reservedEstimate) <= pendingLimitTokens
                ) {
                    pendingExposureTokens = saturatingAdd(pendingExposureTokens, reservedEstimate)
                    reservedRequests += 1
                    reservedInSlice += 1
                    reservedSlice = currentSlice
                    true
                } else {
                    false
                }
            }
            if (reserved) {
                return Lease(this, rawEstimate, reservedEstimate, normalizedCalibrationKey, reservedSlice)
            }
            delay(PENDING_RECHECK_MILLIS)
        }
    }

    /**
     * Start a bounded Work execution slice without blocking on previous-slice subagents.
     * Each outstanding lease remembers its own slice. Total pending token exposure remains
     * shared across slices so parallel usage is still bounded.
     */
    @Synchronized
    fun beginExecutionSlice(policy: LocalModelBudgetPolicy? = null): Boolean {
        // A newly selected model owns the next slice; outstanding leases retain their original
        // slice identities. Pending exposure and usage never reset across a route change.
        policy?.let {
            maxRequests = it.workRequestsPerSlice.coerceIn(1, 512)
            pendingLimitTokens = it.pendingWorkTokens.coerceAtLeast(DEFAULT_PENDING_LIMIT_TOKENS)
        }
        currentSlice += 1
        admittedRequests = 0
        reservedInSlice = 0
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        committedExposureTokens = committedExposureTokens(),
        reportedExposureTokens = reportedExposureTokens,
        uncertainExposureTokens = uncertainExposureTokens,
        pendingExposureTokens = pendingExposureTokens,
        admittedRequests = admittedRequests,
        reservedRequests = reservedRequests,
        maxRequests = maxRequests,
        estimateCalibrationPermille = (
            calibrations[lastCalibrationKey]?.scale?.times(1_000.0) ?: 1_000.0
            ).toInt().coerceAtLeast(1),
        calibrationSamples = calibrations.values.sumOf { it.samples },
        calibrationRoutes = calibrations.size,
    )

    @Synchronized
    private fun commit(
        rawEstimate: Long,
        reservedEstimate: Long,
        reportedInputTokens: Long?,
        calibrationKey: String,
        leaseSlice: Long,
    ) {
        pendingExposureTokens = (pendingExposureTokens - reservedEstimate).coerceAtLeast(0L)
        reservedRequests = (reservedRequests - 1).coerceAtLeast(0)
        if (leaseSlice == currentSlice) {
            reservedInSlice = (reservedInSlice - 1).coerceAtLeast(0)
            admittedRequests += 1
        }
        val reported = reportedInputTokens?.takeIf { it > 0L }
        if (reported != null) {
            reportedExposureTokens = saturatingAdd(reportedExposureTokens, reported)
            updateCalibration(calibrationKey, rawEstimate, reported)
        } else {
            uncertainExposureTokens = saturatingAdd(uncertainExposureTokens, reservedEstimate)
        }
    }

    @Synchronized
    private fun release(reservedEstimate: Long, leaseSlice: Long) {
        pendingExposureTokens = (pendingExposureTokens - reservedEstimate).coerceAtLeast(0L)
        reservedRequests = (reservedRequests - 1).coerceAtLeast(0)
        if (leaseSlice == currentSlice) {
            reservedInSlice = (reservedInSlice - 1).coerceAtLeast(0)
        }
    }

    @Synchronized
    private fun calibratedEstimate(rawEstimate: Long, calibrationKey: String): Long {
        val calibration = calibrations[calibrationKey] ?: return rawEstimate
        if (calibration.samples <= 0) return rawEstimate
        return ceil(rawEstimate.toDouble() * calibration.scale)
            .toLong()
            .coerceAtLeast(1L)
    }

    @Synchronized
    private fun updateCalibration(
        calibrationKey: String,
        rawEstimate: Long,
        reportedInputTokens: Long,
    ) {
        if (rawEstimate <= 0L || reportedInputTokens <= 0L) return
        val observed = (reportedInputTokens.toDouble() / rawEstimate.toDouble())
            .coerceIn(MIN_ESTIMATE_SCALE, MAX_ESTIMATE_SCALE)
        val calibration = calibrations.getOrPut(calibrationKey) { EstimateCalibration() }
        calibration.scale = if (calibration.samples == 0) {
            observed
        } else {
            calibration.scale * 0.75 + observed * 0.25
        }
        calibration.samples = (calibration.samples + 1).coerceAtMost(Int.MAX_VALUE)
        lastCalibrationKey = calibrationKey
    }

    @Synchronized
    private fun committedExposureTokens(): Long =
        saturatingAdd(reportedExposureTokens, uncertainExposureTokens)

    private fun budgetExceeded(detail: String) = LocalModelException(
        code = "WORK_BUDGET_EXHAUSTED",
        message = "$detail；当前执行片会保存已有进度；无排队用户输入时可安全续接未完成部分",
        retryable = false,
        // This guard is per execution slice; resume from the durable checkpoint, never replay the request.
        continuationEligible = true,
    )

    class Lease internal constructor(
        private val owner: LocalWorkExecutionBudget,
        private val rawEstimate: Long,
        private val reservedEstimate: Long,
        private val calibrationKey: String,
        private val leaseSlice: Long,
    ) {
        private val settled = AtomicBoolean(false)

        fun commit(reportedInputTokens: Long? = null) {
            if (settled.compareAndSet(false, true)) {
                owner.commit(rawEstimate, reservedEstimate, reportedInputTokens, calibrationKey, leaseSlice)
            }
        }

        fun release() {
            if (settled.compareAndSet(false, true)) owner.release(reservedEstimate, leaseSlice)
        }
    }

    data class Snapshot(
        val committedExposureTokens: Long,
        val reportedExposureTokens: Long,
        val uncertainExposureTokens: Long,
        val pendingExposureTokens: Long,
        val admittedRequests: Int,
        val reservedRequests: Int,
        val maxRequests: Int,
        val estimateCalibrationPermille: Int,
        val calibrationSamples: Int,
        val calibrationRoutes: Int,
    )

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private companion object {
        const val DEFAULT_PENDING_LIMIT_TOKENS = 512_000L
        const val DEFAULT_MAX_REQUESTS = 128
        const val PENDING_RECHECK_MILLIS = 100L
        const val MIN_ESTIMATE_SCALE = 0.35
        const val MAX_ESTIMATE_SCALE = 1.25
        const val DEFAULT_CALIBRATION_KEY = "default"
    }
}

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
    com.labteto.dshmobile.local.agent.DEFAULT_AGENT_CONTINUATION_POLICY.allows(
        error = error,
        completedContinuations = automaticContinuationCount,
    ) && pendingInputs == 0

internal class LocalWorkExecutionControl(
    val budget: LocalWorkExecutionBudget = LocalWorkExecutionBudget(),
)

internal suspend fun executeWithModelAdmission(
    control: LocalWorkExecutionControl?,
    routeFingerprint: String,
    model: String,
    baseUrl: String,
    contextWindowTokensOverride: Int? = null,
    operationalInputLimitSnapshot: Int? = null,
    contextWindowSnapshot: Int? = null,
    messages: List<kotlinx.serialization.json.JsonObject>,
    tools: kotlinx.serialization.json.JsonArray,
    block: suspend () -> LocalModelReply,
): LocalModelReply {
    val pressure = LocalPromptPressureMeter.measure(
        messages = messages,
        tools = tools,
        operationalLimitTokens = operationalInputLimitSnapshot
            ?: operationalInputLimitTokens(model, baseUrl, contextWindowTokensOverride),
        modelContextWindowTokens = contextWindowSnapshot
            ?: documentedContextWindowTokens(model, baseUrl, contextWindowTokensOverride),
    )
    if (pressure.estimatedInputTokens > pressure.operationalLimitTokens) {
        throw LocalModelException(
            code = "MODEL_CONTEXT_BUDGET_EXCEEDED",
            message = "预计输入 ${pressure.estimatedInputTokens} token，超过当前路由安全上限 ${pressure.operationalLimitTokens}",
            retryable = false,
        )
    }
    val lease = control?.budget?.reserve(pressure.estimatedInputTokens, routeFingerprint)
    try {
        val reply = block()
        lease?.commit(reply.usage.promptTokens.takeIf { reply.usage.reported })
        return reply
    } catch (cancelled: CancellationException) {
        when ((cancelled as? LocalModelCancellationException)?.admissionState) {
            LocalModelAdmissionState.NOT_SENT,
            LocalModelAdmissionState.REJECTED -> lease?.release()
            else -> lease?.commit()
        }
        throw cancelled
    } catch (error: LocalModelException) {
        when (error.admissionState) {
            LocalModelAdmissionState.NOT_SENT,
            LocalModelAdmissionState.REJECTED -> lease?.release()
            else -> lease?.commit()
        }
        throw error
    } catch (error: Throwable) {
        lease?.commit()
        throw error
    }
}
