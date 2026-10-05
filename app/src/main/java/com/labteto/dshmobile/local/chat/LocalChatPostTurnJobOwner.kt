package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job

/**
 * Process-local owner of the one scheduled direct-chat post-turn consolidation job.
 *
 * Foreground Chat stop/edit/regenerate operations cancel through this owner without reaching into
 * LocalHarnessEngine. The coordinator remains responsible for creating jobs; ownership lives here.
 */
internal object LocalChatPostTurnJobOwner {
    private val lock = Any()
    private var scheduled: Job? = null

    internal fun replace(job: Job) {
        val previous = synchronized(lock) {
            val current = scheduled
            scheduled = job
            current
        }
        if (previous !== job) previous?.cancel()
    }

    internal fun clearIf(job: Job) {
        synchronized(lock) {
            if (scheduled === job) scheduled = null
        }
    }

    internal fun cancel() {
        val job = synchronized(lock) {
            val current = scheduled
            scheduled = null
            current
        }
        job?.cancel()
    }
}
