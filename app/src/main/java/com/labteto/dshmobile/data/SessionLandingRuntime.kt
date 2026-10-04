package com.labteto.dshmobile.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes "last opened session" persistence so an older slow write cannot win after a newer
 * session was opened on the same host.
 */
internal class SessionLandingRuntime(
    private val activeHostKey: () -> String?,
    private val currentSessionId: () -> String?,
    private val persist: suspend (hostKey: String, sessionId: String) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
) {
    private val writeMutex = Mutex()

    suspend fun remember(sessionId: String) {
        writeMutex.withLock {
            if (currentSessionId() != sessionId) return
            val hostKey = activeHostKey() ?: return
            runCatching { persist(hostKey, sessionId) }
                .onFailure { logger("could not remember last session", it) }
        }
    }
}
