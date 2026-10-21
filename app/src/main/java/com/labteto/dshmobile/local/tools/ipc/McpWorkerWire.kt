package com.labteto.dshmobile.local.tools.ipc

/** Compact versioned app-internal Binder/Messenger protocol. Never exposes a tool registry. */
internal object McpWorkerWire {
    const val VERSION = 1
    const val OPEN = 1
    const val REQUEST = 2
    const val CLOSE = 3
    const val RESPONSE = 4
    const val TERMINATED = 5
    const val UPDATE_AUTH = 6

    // Binder calls share an OS transaction budget with framework metadata. A too-large
    // response must fail explicitly, never silently truncate a command or claim success.
    const val MAX_REQUEST_BYTES = 48 * 1024
    const val MAX_INLINE_RESPONSE_BYTES = 48 * 1024
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    const val MAX_PENDING_CALLS = 16
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val REQUEST_TIMEOUT_MS = 120_000L

    fun isDedicatedProcess(hostPid: Int, workerPid: Int): Boolean =
        hostPid > 0 && workerPid > 0 && hostPid != workerPid

    fun isAllowedMethod(method: String): Boolean = method.isNotBlank() &&
        method.length <= 96 && method.none(Char::isISOControl)
    fun validRequestSize(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES
    fun validResponseSize(value: String): Boolean =
        value.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES
}
