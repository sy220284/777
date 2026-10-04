package com.labteto.dshmobile.local

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

internal enum class LocalSessionRuntimeKind {
    AUTOMATION_CHAT,
    AUTOMATION_WORK,
}

/**
 * Process-local session execution ownership.
 *
 * A live owner means the durable tail belongs to an in-process run and must not be repaired as a
 * crash tail by a concurrently opened UI session. Different automation tasks targeting the same
 * session are serialized through the same mutex.
 */
internal class LocalSessionRuntimeLease internal constructor(
    private val sessionId: String,
    private val kind: LocalSessionRuntimeKind,
    private val lock: Mutex,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        LocalSessionRuntimeRegistry.release(sessionId, kind)
        lock.unlock()
    }
}

internal object LocalSessionRuntimeRegistry {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val owners = ConcurrentHashMap<String, LocalSessionRuntimeKind>()

    fun hasLiveOwner(sessionId: String): Boolean =
        sessionId.isNotBlank() && owners.containsKey(sessionId)

    suspend fun acquire(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
    ): LocalSessionRuntimeLease {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        lock.lock()
        owners[sessionId] = kind
        return LocalSessionRuntimeLease(sessionId, kind, lock)
    }

    internal fun release(sessionId: String, kind: LocalSessionRuntimeKind) {
        owners.remove(sessionId, kind)
    }
}
