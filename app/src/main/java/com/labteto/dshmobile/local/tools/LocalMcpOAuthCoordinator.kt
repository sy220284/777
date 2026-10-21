package com.labteto.dshmobile.local.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.labteto.dshmobile.local.io.readBoundedLine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** User-triggered connection login. No model tool can choose or inject an authorization target. */
@Singleton
internal class LocalMcpOAuthCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: LocalMcpCredentialStore,
) {
    private val pending = ConcurrentHashMap<String, Job>()
    fun cancel(serverId: String) { pending[serverId]?.cancel() }

    suspend fun authorize(serverId: String, endpoint: String, suppliedClientId: String? = null) {
        val job = currentCoroutineContext().job
        check(pending.putIfAbsent(serverId, job) == null) { "该服务已有进行中的登录" }
        try {
            val protocol = credentials.oauthProtocol
            val hint = credentials.challenge(serverId, endpoint)
            val info = protocol.discover(endpoint, hint?.first, hint?.second)
            CallbackListener().use { listener ->
                val previous = credentials.oauthForEndpoint(serverId, endpoint)
                // Registration is issuer-scoped. A changed issuer cannot inherit its client ID.
                val clientId = suppliedClientId?.trim()?.takeIf(String::isNotBlank)
                    ?: previous?.takeIf { it.issuer == info.issuer }?.clientId
                    ?: protocol.register(info, listener.redirectUri)
                val state = LocalMcpOAuthProtocol.nonce()
                val verifier = LocalMcpOAuthProtocol.nonce()
                val url = protocol.authorizationUrl(info, clientId, listener.redirectUri, state, verifier)
                currentCoroutineContext().ensureActive()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val callback = withTimeout(3 * 60_000L) { listener.await(state) }
                val code = LocalMcpOAuthProtocol.validateCallback(callback, state, info.issuer, info.issuerInResponseRequired)
                val credential = protocol.exchange(info, clientId, listener.redirectUri, verifier, code)
                currentCoroutineContext().ensureActive()
                credentials.saveOAuth(serverId, endpoint, credential)
            }
        } finally { pending.remove(serverId, job) }
    }

    /** A browser may probe /favicon.ico or an unrelated app may hit the loopback port.
     * Only an exact state match can consume this single-use callback. */
    internal class CallbackListener : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val path = "/mcp-callback"
        val redirectUri = "http://127.0.0.1:${server.localPort}$path"

        suspend fun await(expectedState: String): Map<String, String> = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            Thread({
                try {
                    while (continuation.isActive && !server.isClosed) {
                        val accepted = server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val line = readBoundedLine(socket.getInputStream()
                                .bufferedReader(Charsets.UTF_8), 16_384).orEmpty()
                            val parts = line.split(' ')
                            val target = parts.getOrNull(1).orEmpty()
                            val values = linkedMapOf<String, String>()
                            var wellFormed = parts.size >= 2 && parts[0] == "GET" &&
                                target.substringBefore('?') == path && target.contains('?')
                            if (wellFormed) {
                                for (item in target.substringAfter('?').split('&').filter(String::isNotBlank)) {
                                    val key = URLDecoder.decode(item.substringBefore('='), "UTF-8")
                                    val value = URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
                                    if (values.put(key, value) != null) wellFormed = false
                                }
                            }
                            val valid = wellFormed && values["state"] == expectedState
                            val status = if (valid) "200 OK" else "400 Bad Request"
                            val body = (if (valid) "授权回调已接收，请返回神言神语查看验证和连接结果。"
                                else "无效的授权回调。").toByteArray(Charsets.UTF_8)
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                                    .toByteArray(Charsets.US_ASCII))
                                write(body); flush()
                            }
                            values.takeIf { valid }
                        }
                        if (accepted != null) {
                            if (continuation.isActive) continuation.resume(accepted)
                            break
                        }
                    }
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally { close() }
            }, "mcp-oauth-callback").apply { isDaemon = true; start() }
        }

        override fun close() { server.close() }
    }
}
