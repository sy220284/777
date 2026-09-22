package com.labteto.dshmobile.automation

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookListenerTest {
    @Test fun repeatedRestartReleasesPortAndServesOnlyCurrentListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val port = ServerSocket(0).use { it.localPort }
        val listener = WebhookListener(scope) { it.getOutputStream().write(42) }
        try {
            repeat(10) {
                val currentPort = ServerSocket(0).use { it.localPort }
                listener.restart(InetSocketAddress("127.0.0.1", currentPort))
                Socket("127.0.0.1", currentPort).use { client ->
                    client.soTimeout = 3000
                    assertEquals(42, client.getInputStream().read())
                }
            }
            listener.close()
            ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress("127.0.0.1", port)) }
        } finally { listener.close(); scope.cancel() }
    }
}
