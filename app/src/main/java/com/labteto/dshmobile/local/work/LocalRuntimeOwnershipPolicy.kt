package com.labteto.dshmobile.local.work



/**
 * Keeps persisted recovery separate from an in-memory Work runtime that still owns the session.
 *
 * A live Work binding is authoritative until its Job reaches a terminal state. Re-entering that
 * session only rebinds the visible projection and must never repair the durable tail as if the
 * process had died, nor may the visible/global queue start a second foreground run for it.
 */
internal object LocalRuntimeOwnershipPolicy {
    fun allowPersistedRecovery(hasLiveWorkOwner: Boolean): Boolean = !hasLiveWorkOwner

    fun allowVisibleQueuedTurn(
        sessionTransitioning: Boolean,
        visibleRunActive: Boolean,
        liveWorkOwner: Boolean,
    ): Boolean = !sessionTransitioning && !visibleRunActive && !liveWorkOwner
}
