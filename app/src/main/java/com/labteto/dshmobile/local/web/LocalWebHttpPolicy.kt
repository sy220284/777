package com.labteto.dshmobile.local.web

import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException

internal const val LOCAL_WEB_USER_AGENT = "DSH-Mobile-Android16/0.12.0"
internal const val LOCAL_WEB_MAX_RESPONSE_BYTES = 4 * 1024 * 1024

/** Shared bounded response read for safe HTTP and auxiliary search. Reads at most limit + 1. */
internal fun readBoundedWebBody(input: java.io.InputStream, maxBytes: Int): BoundedWebBody {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8_192)
    var remaining = maxBytes + 1
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    val all = output.toByteArray()
    val truncated = all.size > maxBytes
    return BoundedWebBody(
        bytes = if (truncated) all.copyOf(maxBytes) else all,
        truncated = truncated,
    )
}


internal data class BoundedWebBody(val bytes: ByteArray, val truncated: Boolean)

internal fun isRetryableWebTransportFailure(error: java.io.IOException): Boolean =
    error !is UnknownHostException && error !is SSLPeerUnverifiedException
