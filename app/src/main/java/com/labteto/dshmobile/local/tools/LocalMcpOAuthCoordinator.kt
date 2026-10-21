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
                val callback = withTimeout(3 * 60_000L) { listener.await() }
                val code = LocalMcpOAuthProtocol.validateCallback(callback, state, info.issuer, info.issuerInResponseRequired)
                val credential = protocol.exchange(info, clientId, listener.redirectUri, verifier, code)
                currentCoroutineContext().ensureActive()
                credentials.saveOAuth(serverId, endpoint, credential)
            }
        } finally { pending.remove(serverId, job) }
    }

    private class CallbackListener : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val path = "/mcp-callback"
        val redirectUri = "http://127.0.0.1:${server.localPort}$path"
        suspend fun await(): Map<String, String> = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            Thread({
                try {
                    val result = server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val line = readBoundedLine(socket.getInputStream().bufferedReader(Charsets.UTF_8), 16_384)
                            ?: error("MCP 授权回调为空")
                        val pieces = line.split(' ')
                        require(pieces.size >= 2 && pieces[0] == "GET") { "MCP 授权回调无效" }
                        val target = pieces[1]
                        require(target.substringBefore('?') == path) { "MCP 授权回调路径不匹配" }
                        val values = linkedMapOf<String, String>()
                        target.substringAfter('?', "").split('&').filter(String::isNotBlank).forEach { item ->
                            val key = URLDecoder.decode(item.substringBefore('='), "UTF-8")
                            val value = URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
                            require(values.put(key, value) == null) { "MCP 授权回调包含重复参数" }
                        }
                        val body = "授权回调已接收，请返回神言神语查看验证和连接结果。".toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(body); flush()
                        }
                        values
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                } finally { close() }
            }, "mcp-oauth-callback").apply { isDaemon = true; start() }
        }
        override fun close() { server.close() }
    }
}
