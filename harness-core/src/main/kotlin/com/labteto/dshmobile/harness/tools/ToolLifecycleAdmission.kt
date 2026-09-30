package com.labteto.dshmobile.harness.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Stops new calls and drains admitted calls before any provider resource is closed. */
internal class ToolLifecycleAdmission {
    private var safe = true
    private var paused = false
    private var active = 0
    private var drained: CompletableDeferred<Unit>? = null
    @Synchronized fun beginCall(): Boolean {
        if (paused || !safe) return false
        active++
        return true
    }
    @Synchronized fun endCall() {
        check(active > 0)
        active--
        if (active == 0) drained?.complete(Unit)
    }
    @Synchronized fun markSafe(value: Boolean) { safe = value }
    suspend fun <T> transition(block: suspend () -> T): T {
        val wait = synchronized(this) {
            check(!paused)
            paused = true
            if (active == 0) null else CompletableDeferred<Unit>().also { drained = it }
        }
        try {
            // A pending approval or broken executor must not hold lifecycle mutation forever.
            withTimeout(30_000L) { wait?.await() }
            return block()
        } finally {
            synchronized(this) { paused = false; drained = null }
        }
    }
}
