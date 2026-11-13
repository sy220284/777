package com.labteto.dshmobile.automation

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serializes binding and closes accepted sockets when a listener generation is replaced. */
internal class WebhookListener(
    private val scope: CoroutineScope,
    private val maxClients: Int = DEFAULT_MAX_CLIENTS,
    private val handle: suspend (Socket) -> Unit,
) : AutoCloseable {
    init {
        require(maxClients in 1..1_024) { "Webhook 连接上限必须在 1..1024 之间" }
    }

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val clients = mutableSetOf<Socket>()

    @Synchronized fun restart(address: InetSocketAddress) {
        val current = server
        if (
            current != null &&
            !current.isClosed &&
            current.localSocketAddress == address
        ) {
            return
        }
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
        val generationLimiter = WebhookConnectionLimiter(maxClients)
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                synchronized(this@WebhookListener) {
                    when {
                        server !== socket -> client.close()
                        !generationLimiter.tryAcquire() -> client.close()
                        else -> {
                            clients += client
                            launch {
                                try {
                                    client.use { handle(it) }
                                } finally {
                                    generationLimiter.release()
                                    synchronized(this@WebhookListener) { clients.remove(client) }
                                }
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

    private companion object {
        const val DEFAULT_MAX_CLIENTS = 64
    }
}

internal class WebhookConnectionLimiter(
    private val maxClients: Int,
) {
    private val active = AtomicInteger(0)

    init {
        require(maxClients in 1..1_024) { "Webhook 连接上限必须在 1..1024 之间" }
    }

    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= maxClients) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    fun release(): Boolean {
        while (true) {
            val current = active.get()
            if (current <= 0) return false
            if (active.compareAndSet(current, current - 1)) return true
        }
    }

    fun activeCount(): Int = active.get()
}
