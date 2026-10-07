package com.labteto.dshmobile.local.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One Work-owned refresh consumer coalesces progress while terminal notifications wake it early. */
internal class LocalTeamRefreshQueue(
    scope: CoroutineScope,
    private val refresh: (sessionId: String, reconcile: Boolean) -> Unit,
) {
    private val lock = Any()
    private val pending = linkedMapOf<String, Boolean>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wake) {
                if (!synchronized(lock) { pending.values.any { it } }) {
                    withTimeoutOrNull(250L) {
                        do { wake.receive() } while (!synchronized(lock) { pending.values.any { it } })
                    }
                }
                val batch = synchronized(lock) { pending.toMap().also { pending.clear() } }
                batch.forEach { (session, reconcile) -> refresh(session, reconcile) }
            }
        }
    }

    fun schedule(sessionId: String, reconcile: Boolean) {
        synchronized(lock) { pending[sessionId] = pending[sessionId] == true || reconcile }
        wake.trySend(Unit)
    }
}
