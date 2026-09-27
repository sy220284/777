package com.labteto.dshmobile.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/**
 * Coalesces repeated refresh requests without dropping a request that arrives while one is running.
 *
 * One caller owns the mutex and drains the dirty bit. Contending callers only mark the gate dirty;
 * the owner performs one additional pass before releasing, so bursts collapse without losing the
 * final state change.
 */
internal class ConflatedRefreshGate {
    private val mutex = Mutex()
    private val dirty = AtomicBoolean(false)

    suspend fun request(block: suspend () -> Unit) {
        dirty.set(true)
        if (!mutex.tryLock()) return
        try {
            while (dirty.getAndSet(false)) {
                block()
            }
        } finally {
            mutex.unlock()
            // Cover the narrow race between the last dirty read and unlock.
            if (dirty.get()) request(block)
        }
    }
}
