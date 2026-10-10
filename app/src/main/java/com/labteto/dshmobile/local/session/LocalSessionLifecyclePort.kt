package com.labteto.dshmobile.local.session

import com.labteto.dshmobile.local.LocalUsageMode

/**
 * Session lifecycle entry owned by the Session capability.
 *
 * Consumers receive only the Session capability contract and never obtain Feature internals or a
 * writable aggregate-state handle.
 */
internal interface LocalSessionLifecyclePort {
    fun createSession(
        mode: LocalConversationMode,
        usageMode: LocalUsageMode,
        domainSpec: LocalSessionDomainCreateSpec? = null,
        handoffSummaryOverride: String? = null,
    ): Boolean

    fun currentHandoffSummary(): String

    fun switchDomainMode(command: LocalSessionDomainModeCommand)

    fun switchUsageMode(mode: LocalUsageMode)

    fun switchSession(sessionId: String): Boolean

    suspend fun deleteSessions(ids: Set<String>): Int
}
