package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode

/**
 * Session lifecycle entry owned by the Session capability.
 *
 * The implementation may temporarily be composed beside the legacy Engine while Stage 3 is in
 * progress, but consumers never receive Engine or a writable aggregate-state handle.
 */
internal interface LocalSessionLifecyclePort {
    fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec? = null,
    ): Boolean

    fun switchDomainMode(command: LocalSessionDomainModeCommand)

    fun switchUsageMode(mode: LocalUsageMode)

    fun switchSession(sessionId: String): Boolean

    suspend fun deleteSessions(ids: Set<String>): Int
}
