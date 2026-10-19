package com.labteto.dshmobile.automation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

internal suspend fun executeWebhookRun(
    update: (status: WebhookRunStatus, result: String?, error: String?) -> Unit,
    run: suspend () -> String,
) {
    update(WebhookRunStatus.RUNNING, null, null)
    val output = try {
        run()
    } catch (cancelled: CancellationException) {
        // RUNNING means the action could have reached external systems already.
        // Unlike an unstarted queued request, cancellation cannot prove no side effect.
        update(WebhookRunStatus.INTERRUPTED, null, "Webhook 执行中断，外部副作用可能已发生，请先核实")
        throw cancelled
    } catch (error: Exception) {
        update(WebhookRunStatus.FAILED, null, error.message ?: error::class.java.simpleName)
        return
    }
    // This is outside the execution catch: receipt persistence is not the run's outcome.
    // A successful side effect must never be rewritten to FAILED if its receipt fails.
    update(WebhookRunStatus.COMPLETED, output, null)
}


internal class WebhookExecutionLimiter(
    private val maxPending: Int,
) {
    private val pending = AtomicInteger(0)

    init {
        require(maxPending in 1..1_024) { "Webhook 等待队列上限必须在 1..1024 之间" }
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = pending.get()
            if (current >= maxPending) return false
            if (pending.compareAndSet(current, current + 1)) return true
        }
    }

    fun release(): Boolean {
        while (true) {
            val current = pending.get()
            if (current <= 0) return false
            if (pending.compareAndSet(current, current - 1)) return true
        }
    }

    fun pendingCount(): Int = pending.get()
}


internal fun shouldMarkWebhookQueuedCancellation(
    cause: Throwable?,
    currentStatus: WebhookRunStatus?,
): Boolean =
    cause is CancellationException && currentStatus == WebhookRunStatus.QUEUED
