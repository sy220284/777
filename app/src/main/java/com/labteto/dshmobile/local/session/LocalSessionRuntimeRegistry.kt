package com.labteto.dshmobile.local

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

internal enum class LocalSessionRuntimeKind {
    AUTOMATION_CHAT,
    AUTOMATION_WORK,
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
    fun hasLiveOwner(sessionId: String): Boolean = entries[sessionId]?.owner != null

    @Synchronized
    internal fun retainedSessionCount(): Int = entries.size

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
        return LocalSessionRuntimeLease {
            synchronized(this) { entry.owner = null }
            entry.mutex.unlock()
            releaseReservation(sessionId, entry)
        }
    }

    @Synchronized
    private fun releaseReservation(sessionId: String, entry: Entry) {
        entry.reservations--
        if (entry.reservations == 0 && entries[sessionId] === entry) entries.remove(sessionId)
    }
}
