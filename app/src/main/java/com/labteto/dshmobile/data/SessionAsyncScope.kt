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

internal fun <T : Any> isCurrentHostRequest(
    hostKey: String?,
    capturedApi: T,
    activeHostKey: () -> String?,
    apiForHost: (String?) -> T?,
): Boolean =
    hostKey == activeHostKey() && apiForHost(hostKey) === capturedApi
