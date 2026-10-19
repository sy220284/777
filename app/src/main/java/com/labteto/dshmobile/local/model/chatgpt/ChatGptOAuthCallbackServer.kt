package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.io.readBoundedLine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class ChatGptOAuthCallbackServer @Inject constructor() {
    fun open(): Listener {
        val server = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        // ServerSocket.accept() is not reliably interrupted by coroutine cancellation.
        // A short socket timeout lets the OAuth wait honor its deadline and cancellation.
        server.soTimeout = ACCEPT_POLL_MILLIS
        return Listener(server)
    }

    class Listener internal constructor(
        private val server: ServerSocket,
    ) : AutoCloseable {
        val redirectUri: String = "http://$LOOPBACK:${server.localPort}$CALLBACK_PATH"

        internal suspend fun await(timeoutMillis: Long = CALLBACK_TIMEOUT_MILLIS): ChatGptOAuthCallback =
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    var received: ChatGptOAuthCallback? = null
                    while (received == null) {
                        currentCoroutineContext().ensureActive()
                        val client = try {
                            server.accept()
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        client.use { socket ->
                            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                            // Browser probes and unrelated localhost requests are not OAuth callbacks.
                            val callback = readCallback(socket)
                            if (callback != null) {
                                writeCompletionPage(socket, callback.error == null)
                                received = callback
                            }
                        }
                    }
                    checkNotNull(received)
                }
            }

        override fun close() {
            runCatching { server.close() }
        }

        private fun readCallback(socket: Socket): ChatGptOAuthCallback? {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") return null
            val target = parts[1]
            val path = target.substringBefore('?')
            if (path != CALLBACK_PATH) return null
            val query = target.substringAfter('?', "")
            val params = parseQuery(query)
            return ChatGptOAuthCallback(
                code = params["code"],
                state = params["state"],
                clientId = params["client_id"],
                scope = params["scope"],
                error = params["error"],
                errorDescription = params["error_description"],
            )
        }

        private fun writeCompletionPage(socket: Socket, success: Boolean) {
            val title = if (success) "授权信息已返回应用" else "授权未完成"
            val body = if (success) "应用正在验证身份并保存账户。请返回神言神语查看最终连接状态。" else "授权未完成，请返回神言神语查看详情。"
            val html = "<!doctype html><meta charset=\"utf-8\"><title>$title</title>" +
                "<body style=\"font-family:sans-serif;padding:32px\"><h2>$title</h2><p>$body</p></body>"
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().use { output ->
                output.write(header.toByteArray(StandardCharsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
        }
    }

    companion object {
        internal const val LOOPBACK = "127.0.0.1"
        internal const val CALLBACK_PATH = "/auth/callback"
        private const val CALLBACK_TIMEOUT_MILLIS = 3 * 60_000L
        private const val ACCEPT_POLL_MILLIS = 250
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        private const val MAX_REQUEST_LINE_CHARS = 16_384

        internal fun parseQuery(query: String): Map<String, String> =
            query.split('&')
                .mapNotNull { entry ->
                    if (entry.isBlank()) return@mapNotNull null
                    val name = entry.substringBefore('=')
                    val value = entry.substringAfter('=', "")
                    decode(name) to decode(value)
                }
                .toMap()

        private fun decode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
