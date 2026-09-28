package com.labteto.dshmobile.automation

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookListenerTest {
    @Test fun repeatedRestartReleasesEveryPreviousPortAndServesOnlyCurrentListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = WebhookListener(scope) { it.getOutputStream().write(42) }
        val usedPorts = mutableListOf<Int>()
        try {
            repeat(100) {
                val currentPort = ServerSocket(0).use { it.localPort }
                listener.restart(InetSocketAddress("127.0.0.1", currentPort))
                usedPorts += currentPort

                Socket("127.0.0.1", currentPort).use { client ->
                    client.soTimeout = 3000
                    assertEquals(42, client.getInputStream().read())
                }

                if (usedPorts.size > 1) {
                    val previousPort = usedPorts[usedPorts.lastIndex - 1]
                    ServerSocket().use { probe ->
                        probe.reuseAddress = true
                        probe.bind(InetSocketAddress("127.0.0.1", previousPort))
                    }
                }
            }

            val finalPort = usedPorts.last()
            listener.close()
            ServerSocket().use { probe ->
                probe.reuseAddress = true
                probe.bind(InetSocketAddress("127.0.0.1", finalPort))
            }
        } finally {
            listener.close()
            scope.cancel()
        }
    }

    @Test
    fun connectionLimiterRejectsSlowConnectionFloodAndRecovers() {
        val limiter = WebhookConnectionLimiter(3)

        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        assertEquals(3, limiter.activeCount())

        assertTrue(limiter.release())
        assertTrue(limiter.tryAcquire())
        assertEquals(3, limiter.activeCount())
    }

    @Test
    fun connectionLimiterNeverUnderflowsOnExtraRelease() {
        val limiter = WebhookConnectionLimiter(1)

        assertFalse(limiter.release())
        assertEquals(0, limiter.activeCount())
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.release())
        assertFalse(limiter.release())
        assertEquals(0, limiter.activeCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun connectionLimiterRejectsZeroCapacity() {
        WebhookConnectionLimiter(0)
    }

}
