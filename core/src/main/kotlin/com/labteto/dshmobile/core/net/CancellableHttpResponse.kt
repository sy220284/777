package com.labteto.dshmobile.core.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Response

/** Owns cancellation and response cleanup from connect through the last body read/write. */
suspend fun <T> withCancellableHttpResponse(
    call: Call,
    cancelResources: () -> Unit = { },
    read: suspend (Response) -> T,
): T = withContext(Dispatchers.IO) {
    coroutineScope {
        ensureActive()
        val owner = currentCoroutineContext()
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
                if (!owner.isActive) runCatching { cancelResources() }
            }
        }
        var response: Response? = null
        try {
            val opened = call.execute()
            response = opened
            ensureActive()
            read(opened).also { ensureActive() }
        } catch (error: Exception) {
            // Cancellation-induced socket failures must never become retryable transport errors.
            ensureActive()
            throw error
        } finally {
            // A late close failure must not turn a successful side effect into a duplicate retry.
            runCatching { response?.close() }
            cancellation.cancel()
        }
    }
}

/** Do not deliver bytes to a file consumer after cancellation, even from a buffered/cache source. */
internal fun java.io.InputStream.checkingCancellation(
    context: kotlin.coroutines.CoroutineContext,
): java.io.InputStream = object : java.io.FilterInputStream(this) {
    override fun read(): Int {
        context.ensureActive()
        return super.read().also { context.ensureActive() }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        context.ensureActive()
        return `in`.read(buffer, offset, length).also { context.ensureActive() }
    }

    override fun skip(count: Long): Long {
        context.ensureActive()
        return super.skip(count).also { context.ensureActive() }
    }
}
