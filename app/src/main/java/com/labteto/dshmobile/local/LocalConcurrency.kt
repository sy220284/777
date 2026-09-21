package com.labteto.dshmobile.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope

/**
 * Run sibling tasks concurrently without allowing one ordinary failure/cancellation to cancel
 * the rest. Parent cancellation still propagates so Stop/New Session remains authoritative.
 */
internal suspend fun <T, R> isolatedParallelMap(
    items: List<T>,
    block: suspend (T) -> R,
): List<Result<R>> = supervisorScope {
    items.map { item ->
        async {
            try {
                Result.success(block(item))
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                Result.failure(cancelled)
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }.map { it.await() }
}
