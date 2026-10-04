package com.labteto.dshmobile.data

internal data class SessionAsyncScope(
    val hostKey: String?,
    val sessionId: String? = null,
) {
    fun isCurrent(
        activeHostKey: () -> String?,
        currentSessionId: () -> String?,
    ): Boolean =
        hostKey == activeHostKey() &&
            (sessionId == null || sessionId == currentSessionId())
}
