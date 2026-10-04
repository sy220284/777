package com.labteto.dshmobile.local

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
internal object LocalSessionRuntimeRegistry {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val owners = ConcurrentHashMap<String, LocalSessionRuntimeKind>()

    fun hasLiveOwner(sessionId: String): Boolean =
        sessionId.isNotBlank() && owners.containsKey(sessionId)

    suspend fun <T> withOwner(
        sessionId: String,
        kind: LocalSessionRuntimeKind,
        block: suspend () -> T,
    ): T {
        require(sessionId.isNotBlank()) { "会话编号不能为空" }
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        return lock.withLock {
            owners[sessionId] = kind
            try {
                block()
            } finally {
                owners.remove(sessionId, kind)
            }
        }
    }
}
