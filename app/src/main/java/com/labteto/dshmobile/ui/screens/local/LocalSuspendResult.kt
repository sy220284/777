package com.labteto.dshmobile.ui.screens.local

import kotlinx.coroutines.CancellationException

/** Result wrapper for suspend UI actions without converting coroutine cancellation into a failure value. */
internal suspend inline fun <T> runSuspendResult(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
