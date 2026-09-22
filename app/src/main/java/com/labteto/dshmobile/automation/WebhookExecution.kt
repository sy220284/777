package com.labteto.dshmobile.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun executeWebhookRun(
    mutex: Mutex,
    update: (status: String, result: String?, error: String?) -> Unit,
    run: suspend () -> String,
) {
    try {
        mutex.withLock {
            update("running", null, null)
            update("completed", run(), null)
        }
    } catch (cancelled: CancellationException) {
        update("cancelled", null, "Webhook 服务已停止")
        throw cancelled
    } catch (error: Exception) {
        update("failed", null, error.message ?: error::class.java.simpleName)
    }
}
