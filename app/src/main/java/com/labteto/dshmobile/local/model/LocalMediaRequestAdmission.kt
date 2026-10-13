package com.labteto.dshmobile.local.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Operation-local request boundary; no secrets and no mutable active-account lookup. */
internal class LocalMediaRequestAdmission(val started: () -> Unit, val succeeded: () -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalMediaRequestAdmission>
}
internal suspend fun markMediaRequestStarted() { currentCoroutineContext()[LocalMediaRequestAdmission]?.started?.invoke() }
internal suspend fun markMediaProviderSucceeded() { currentCoroutineContext()[LocalMediaRequestAdmission]?.succeeded?.invoke() }
internal class LocalMediaRequestRejected(message: String) : IllegalStateException(message)
internal class LocalMediaSaveFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)
internal fun throwMediaHttpFailure(provider: String, code: Int): Nothing {
    val reason = "$provider 请求失败（HTTP $code）"
    if (code in setOf(400, 401, 403, 404, 405, 413, 415, 422, 429)) throw LocalMediaRequestRejected(reason)
    error(reason) // A server-side failure is not affirmative proof that billing never started.
}

/** Do not let connection recovery, 503 follow-ups or authentication replay a charged POST. */
internal fun localMediaOneShotBody(body: okhttp3.RequestBody): okhttp3.RequestBody = object : okhttp3.RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
    override fun isOneShot(): Boolean = true
}
