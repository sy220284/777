package com.labteto.dshmobile.automation

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serializes binding and closes accepted sockets when a listener generation is replaced. */
internal class WebhookListener(
    private val scope: CoroutineScope,
    private val handle: suspend (Socket) -> Unit,
) : AutoCloseable {
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = mutableSetOf<Socket>()

    @Synchronized fun restart(address: InetSocketAddress) {
        close()
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(address)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        server = socket
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                synchronized(this@WebhookListener) {
                    if (server !== socket) client.close() else {
                        clients += client
                        launch {
                            try { client.use { handle(it) } } finally {
                                synchronized(this@WebhookListener) { clients.remove(client) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized override fun close() {
        server?.let { runCatching { it.close() } }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        acceptJob?.cancel()
        acceptJob = null
    }
}
