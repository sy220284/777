package com.labteto.dshmobile.automation

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException

internal suspend fun executeWebhookRun(
    update: (status: WebhookRunStatus, result: String?, error: String?) -> Unit,
    run: suspend () -> String,
) {
    try {
        update(WebhookRunStatus.RUNNING, null, null)
        update(WebhookRunStatus.COMPLETED, run(), null)
    } catch (cancelled: CancellationException) {
        update(WebhookRunStatus.CANCELLED, null, "Webhook 服务已停止")
        throw cancelled
    } catch (error: Exception) {
        update(WebhookRunStatus.FAILED, null, error.message ?: error::class.java.simpleName)
    }
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
