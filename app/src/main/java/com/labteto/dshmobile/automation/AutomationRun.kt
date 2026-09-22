package com.labteto.dshmobile.automation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/** A background caller owns its run even though the engine launches it in another scope. */
internal suspend fun <T> owningAutomationRun(job: Job, awaitResult: suspend () -> T): T = try {
    awaitResult()
} catch (cancelled: CancellationException) {
    withContext(NonCancellable) { job.cancelAndJoin() }
    throw cancelled
}
