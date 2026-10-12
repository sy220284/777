package com.labteto.dshmobile.local.chat

import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext

/**
 * One process-local direct-chat reconciliation lane.
 *
 * Each accepted turn is persisted BEFORE enqueue. A later turn coalesces the next job instead
 * of cancelling the job currently consolidating the prior batch. Only foreground preemption,
 * editing, switching and explicit stop cancel the lane; both slots are cancelled together.
 *
 * The job owner does not own durable facts. The event log and generation/sequence cursor do.
 */
internal object LocalChatPostTurnJobOwner {
    private val lock = Any()
    private var active: Job? = null
    private var queued: Job? = null

    /** Jobs must be created LAZY; ownership determines when they may start. */
    internal fun submit(job: Job) {
        var superseded: Job? = null
        val start = synchronized(lock) {
            if (active == null || active?.isCompleted == true) {
                superseded = queued
                queued = null
                active = job
                true
            } else {
                superseded = queued
                queued = job
                false
            }
        }
        if (superseded !== job) superseded?.cancel()
        if (start) job.start()
    }

    /** On normal completion, advance the coalesced queue without losing a pending signal. */
    internal fun clearIf(job: Job) {
        val next = synchronized(lock) {
            when {
                active === job -> {
                    active = queued
                    queued = null
                    active
                }
                queued === job -> {
                    queued = null
                    null
                }
                else -> null
            }
        }
        next?.takeIf { !it.isCancelled }?.start()
    }

    internal fun cancel(): Boolean {
        val jobs = synchronized(lock) {
            val taken = listOfNotNull(active, queued).distinct()
            active = null
            queued = null
            taken
        }
        jobs.forEach(Job::cancel)
        return jobs.isNotEmpty()
    }

    internal suspend fun cancelAndJoin(): Boolean {
        val jobs = synchronized(lock) {
            val taken = listOfNotNull(active, queued).distinct()
            active = null
            queued = null
            taken
        }
        if (jobs.isEmpty()) return false
        withContext(NonCancellable) {
            jobs.forEach { it.cancelAndJoin() }
        }
        return true
    }
}
