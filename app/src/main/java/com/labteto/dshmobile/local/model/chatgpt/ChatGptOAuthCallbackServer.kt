package com.labteto.dshmobile.local.model.chatgpt

import com.labteto.dshmobile.local.io.readBoundedLine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Singleton
class ChatGptOAuthCallbackServer @Inject constructor() {
    fun open(): Listener {
        val server = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
        return Listener(server)
    }

    class Listener internal constructor(
        private val server: ServerSocket,
    ) : AutoCloseable {
        val redirectUri: String = "http://$LOOPBACK:${server.localPort}$CALLBACK_PATH"

        internal suspend fun await(timeoutMillis: Long = CALLBACK_TIMEOUT_MILLIS): ChatGptOAuthCallback =
            withTimeout(timeoutMillis) {
                withContext(Dispatchers.IO) {
                    val client = runInterruptible { server.accept() }
                    client.use { socket ->
                        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                        val callback = readCallback(socket)
                        writeCompletionPage(socket, callback.error == null)
                        callback
                    }
                }
            }

        override fun close() {
            runCatching { server.close() }
        }

        private fun readCallback(socket: Socket): ChatGptOAuthCallback {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS)
                ?: error("ChatGPT OAuth 回调为空")
            val parts = requestLine.split(' ')
            require(parts.size >= 2 && parts[0] == "GET") { "ChatGPT OAuth 回调请求无效" }
            val target = parts[1]
            val path = target.substringBefore('?')
            require(path == CALLBACK_PATH) { "ChatGPT OAuth 回调路径不匹配" }
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
            val title = if (success) "授权回调已接收" else "授权未完成"
            val body = if (success) "请返回神言神语查看身份校验与连接结果。" else "授权未完成，请返回神言神语查看详情。"
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
