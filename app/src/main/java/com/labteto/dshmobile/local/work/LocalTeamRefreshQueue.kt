package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Coalesces progress per session without letting one long history replay block other sessions. */
internal class LocalTeamRefreshQueue(
    scope: CoroutineScope,
    private val refresh: suspend (sessionId: String, reconcile: Boolean) -> Unit,
) {
    private val lock = Any()
    private val pending = linkedMapOf<String, Boolean>()
    private val runningSessions = hashSetOf<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wake) {
                if (!synchronized(lock) { pending.values.any { it } }) {
                    withTimeoutOrNull(250L) {
                        do { wake.receive() } while (!synchronized(lock) { pending.values.any { it } })
                    }
                }
                val batch = synchronized(lock) {
                    val freeSlots = (MAX_PARALLEL_SESSIONS - runningSessions.size).coerceAtLeast(0)
                    pending.entries.asSequence()
                        .filter { it.key !in runningSessions }
                        .take(freeSlots)
                        .map { it.key to it.value }
                        .toList()
                        .also { selected ->
                            selected.forEach { (session, _) ->
                                pending.remove(session)
                                runningSessions.add(session)
                            }
                        }
                }
                batch.forEach { (session, reconcile) ->
                    scope.launch {
                        try {
                            refresh(session, reconcile)
                        } finally {
                            synchronized(lock) { runningSessions.remove(session) }
                            // Wake pending work, including a second refresh queued for this session
                            // during reconstruction, without running two state reducers in parallel.
                            wake.trySend(Unit)
                        }
                    }
                }
            }
        }
    }

    fun schedule(sessionId: String, reconcile: Boolean) {
        synchronized(lock) { pending[sessionId] = pending[sessionId] == true || reconcile }
        wake.trySend(Unit)
    }

    private companion object {
        const val MAX_PARALLEL_SESSIONS = 4
    }
}
