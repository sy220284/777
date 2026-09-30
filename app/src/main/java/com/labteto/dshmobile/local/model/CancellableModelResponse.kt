package com.labteto.dshmobile.local.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/** Cancellation owns the entire response lifetime, including blocking SSE reads after headers. */
internal suspend fun <T> withCancellableModelResponse(
    call: Call,
    read: (Response) -> T,
): T = coroutineScope {
    val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    var response: Response? = null
    try {
        val opened = call.execute()
        response = opened
        read(opened)
    } catch (error: Exception) {
        // A closed socket caused by user cancellation must not become a retryable network error.
        ensureActive()
        throw error
    } finally {
        // Cleanup is best-effort. Once read() has produced a successful protocol result, a late
        // socket/HTTP2 close failure must not overturn success and trigger a duplicate retry.
        runCatching { response?.close() }
        cancellation.cancel()
    }
}

internal fun Response.readBoundedModelError(limit: Int): String {
    require(limit > 0)
    val stream = body?.byteStream() ?: return ""
    val buffer = ByteArray(limit)
    var count = 0
    while (count < limit) {
        val read = stream.read(buffer, count, limit - count)
        if (read < 0) break
        count += read
    }
    return String(buffer, 0, count, Charsets.UTF_8)
}
