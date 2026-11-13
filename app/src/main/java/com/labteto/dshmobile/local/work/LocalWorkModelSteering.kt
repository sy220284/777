package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

/** Model-only interruption; a tool executor is never cancelled or replayed by steering. */
internal sealed interface LocalWorkSamplingOutcome<out T> {
    data class Completed<T>(val value: T) : LocalWorkSamplingOutcome<T>
    data object Steered : LocalWorkSamplingOutcome<Nothing>
}

/**
 * Race one provider sampling against an explicit, DURABLY queued user correction.
 * Await termination of the cancelled provider request before rebuilding model context, so
 * its streaming preview and admitted/uncertain billing diagnostics settle first.
 */
internal suspend fun <T> sampleUntilWorkSteered(
    revisions: StateFlow<Long>,
    observedRevision: Long,
    sample: suspend () -> T,
): LocalWorkSamplingOutcome<T> = supervisorScope {
    if (revisions.value != observedRevision) return@supervisorScope LocalWorkSamplingOutcome.Steered
    val provider = async(start = CoroutineStart.UNDISPATCHED) { sample() }
    val change = async { revisions.first { it != observedRevision } }
    try {
        select<LocalWorkSamplingOutcome<T>> {
            provider.onAwait { LocalWorkSamplingOutcome.Completed(it) }
            change.onAwait { LocalWorkSamplingOutcome.Steered }
        }
    } finally {
        change.cancel()
        // A cancelled sampling may already have consumed tokens: do not claim those were
        // refunded, and do not let a stale completion re-enter the AgentLoop.
        if (!provider.isCompleted) provider.cancelAndJoin()
    }
}
