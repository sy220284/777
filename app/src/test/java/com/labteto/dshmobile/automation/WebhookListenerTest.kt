package com.labteto.dshmobile.automation

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

                // Verify the previous listener is actually released before opening a client
                // connection to the new listener. An outbound client also consumes an ephemeral
                // local port; doing that first can legitimately reuse the just-freed old server
                // port and make this assertion fail even though WebhookListener released it.
                if (usedPorts.size > 1) {
                    val previousPort = usedPorts[usedPorts.lastIndex - 1]
                    ServerSocket().use { probe ->
                        probe.reuseAddress = true
                        probe.bind(InetSocketAddress("127.0.0.1", previousPort))
                    }
                }

                Socket("127.0.0.1", currentPort).use { client ->
                    client.soTimeout = 3000
                    assertEquals(42, client.getInputStream().read())
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


    @Test
    fun restartDoesNotLetOldGenerationConsumeNewConnectionCapacity() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val listener = WebhookListener(
            scope = scope,
            maxClients = 1,
            handle = { socket ->
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                } else {
                    socket.getOutputStream().write(42)
                }
            },
        )
        val firstPort = ServerSocket(0).use { it.localPort }
        val secondPort = ServerSocket(0).use { it.localPort }
        var firstClient: Socket? = null
        try {
            listener.restart(InetSocketAddress("127.0.0.1", firstPort))
            firstClient = Socket("127.0.0.1", firstPort)
            assertTrue(firstEntered.await(3, TimeUnit.SECONDS))

            listener.restart(InetSocketAddress("127.0.0.1", secondPort))
            Socket("127.0.0.1", secondPort).use { secondClient ->
                secondClient.soTimeout = 3_000
                assertEquals(42, secondClient.getInputStream().read())
            }
        } finally {
            releaseFirst.countDown()
            runCatching { firstClient?.close() }
            listener.close()
            scope.cancel()
        }
    }

}
