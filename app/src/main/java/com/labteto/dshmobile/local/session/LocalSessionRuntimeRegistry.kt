package com.labteto.dshmobile.local

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

internal enum class LocalSessionRuntimeKind {
    FOREGROUND,
    AUTOMATION_CHAT,
    AUTOMATION_WORK,
    SESSION_DELETE,
    MAINTENANCE,
}

/** Process-local ownership; queued acquirers retain the same session mutex until they finish. */
internal class LocalSessionRuntimeLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal object LocalSessionRuntimeRegistry {
    private class Entry {
        val mutex = Mutex()
        var reservations = 0
        var owner: LocalSessionRuntimeKind? = null
    }

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun hasLiveOwner(sessionId: String): Boolean = entries[sessionId]?.mutex?.isLocked == true

    @Synchronized
    internal fun retainedSessionCount(): Int = entries.size

    /**
     * Projection reads are allowed during a run, but load-time durable rewrites are not.
     *
     * The callback runs outside the registry monitor while still holding the session mutex, so IO
     * for one session cannot stall ownership checks or acquisition for unrelated sessions.
     */
    fun submitWhenIdle(sessionId: String, submit: () -> Unit): Boolean {
        val lease = tryAcquire(sessionId, LocalSessionRuntimeKind.MAINTENANCE) ?: return false
        return try {
            submit()
            true
        } finally {
            lease.close()
        }
    }

    suspend fun <T> withOwner(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
        block: suspend (String) -> T,
    ): T = withOwner(sessionId, kind, preownedLease = null, block)

    suspend fun <T> withOwner(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
        preownedLease: LocalSessionRuntimeLease?,
        block: suspend (String) -> T,
    ): T {
        val lease = preownedLease ?: acquire(sessionId, kind)
        try {
            return block(sessionId)
        } finally {
            lease.close()
        }
    }

    /**
     * Atomically reserve an idle session without suspension.
     *
     * Foreground admission uses this before mutating transcript/history state so Automation cannot
     * slip between user-input persistence and the actual run.
     */
    fun tryAcquire(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
    ): LocalSessionRuntimeLease? {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        val entry = synchronized(this) {
            val candidate = entries.getOrPut(sessionId, ::Entry)
            // Never barge ahead of an existing owner or suspended acquirer. reservations covers
            // both, including the hand-off window after unlock but before the waiter resumes.
            if (candidate.reservations > 0 || !candidate.mutex.tryLock()) {
                if (candidate.reservations == 0 && entries[sessionId] === candidate) {
                    entries.remove(sessionId)
                }
                return null
            }
            candidate.reservations = 1
            candidate.owner = kind
            candidate
        }
        return lease(sessionId, entry)
    }

    suspend fun acquire(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
    ): LocalSessionRuntimeLease {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        val entry = synchronized(this) {
            entries.getOrPut(sessionId, ::Entry).also { it.reservations++ }
        }
        try {
            entry.mutex.lock()
        } catch (error: Throwable) {
            releaseReservation(sessionId, entry)
            throw error
        }
        synchronized(this) { entry.owner = kind }
        return lease(sessionId, entry)
    }

    /** Hold deletion ownership for every target session in stable order until the caller closes it. */
    suspend fun acquireAll(
        sessionIds: Collection<String>,
        kind: LocalSessionRuntimeKind,
    ): List<LocalSessionRuntimeLease> {
        val leases = mutableListOf<LocalSessionRuntimeLease>()
        try {
            sessionIds.filter(String::isNotBlank).distinct().sorted().forEach { sessionId ->
                leases += acquire(sessionId, kind)
            }
            return leases
        } catch (error: Throwable) {
            leases.asReversed().forEach(LocalSessionRuntimeLease::close)
            throw error
        }
    }

    private fun lease(sessionId: String, entry: Entry): LocalSessionRuntimeLease =
        LocalSessionRuntimeLease {
            synchronized(this) { entry.owner = null }
            entry.mutex.unlock()
            releaseReservation(sessionId, entry)
        }

    @Synchronized
    private fun releaseReservation(sessionId: String, entry: Entry) {
        entry.reservations--
        if (entry.reservations == 0 && entries[sessionId] === entry) entries.remove(sessionId)
    }
}
