package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/**
 * Process-local owner of the one scheduled direct-chat post-turn consolidation job.
 *
 * Foreground Chat stop/edit/regenerate operations cancel through this owner without reaching into
 * the Runtime Kernel. The coordinator remains responsible for creating jobs; ownership lives here.
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

    internal fun cancel(): Boolean {
        val job = takeScheduled()
        job?.cancel()
        return job != null
    }

    internal suspend fun cancelAndJoin(): Boolean {
        val job = takeScheduled() ?: return false
        withContext(NonCancellable) {
            job.cancelAndJoin()
        }
        return true
    }

    private fun takeScheduled(): Job? = synchronized(lock) {
        val current = scheduled
        scheduled = null
        current
    }
}
