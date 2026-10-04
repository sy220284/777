package com.labteto.dshmobile.data

internal data class SessionAsyncScope(
    val hostKey: String?,
    val sessionId: String? = null,
    private val requestCurrent: () -> Boolean = { true },
) {
    fun isCurrent(
        activeHostKey: () -> String?,
        currentSessionId: () -> String?,
    ): Boolean =
        requestCurrent() && hostKey == activeHostKey() &&
            (sessionId == null || sessionId == currentSessionId())
}
