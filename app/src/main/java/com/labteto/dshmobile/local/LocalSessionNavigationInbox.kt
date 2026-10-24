package com.labteto.dshmobile.local

import java.util.concurrent.atomic.AtomicReference
import com.labteto.dshmobile.local.session.LocalSessionDomainModeCommand

/** Latest tap wins across the mode tabs and conversation history. */
internal sealed interface LocalSessionNavigationRequest {
    data class Mode(val value: LocalUsageMode) : LocalSessionNavigationRequest
    data class Session(val id: String) : LocalSessionNavigationRequest
    data class ChatDomain(val command: LocalSessionDomainModeCommand) : LocalSessionNavigationRequest
}

internal class LocalSessionNavigationInbox {
    private val latest = AtomicReference<LocalSessionNavigationRequest?>(null)
    fun offer(request: LocalSessionNavigationRequest) { latest.set(request) }
    fun take(): LocalSessionNavigationRequest? = latest.getAndSet(null)
    /** Non-consuming hint: a new tap supersedes an in-flight, not-yet-activated target. */
    fun hasPending(): Boolean = latest.get() != null
    fun clear() { latest.set(null) }
    fun offerIfEmpty(request: LocalSessionNavigationRequest) {
        latest.compareAndSet(null, request)
    }
}
