package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.core.net.withCancellableHttpResponse
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Response

internal class LocalModelCancellationException(
    val admissionState: LocalModelAdmissionState,
    cause: CancellationException,
) : CancellationException(cause.message) {
    init {
        initCause(cause)
    }
}

/** All model and tool transports share cancellation ownership through the last body read. */
internal suspend fun <T> withCancellableModelResponse(
    call: Call,
    admissionTracker: LocalModelAdmissionTracker? = null,
    read: (Response) -> T,
): T = try {
    withCancellableHttpResponse(call, read = { read(it) })
} catch (cancelled: CancellationException) {
    val tracker = admissionTracker ?: throw cancelled
    throw LocalModelCancellationException(tracker.snapshot(), cancelled)
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
