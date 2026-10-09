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
    @Test
    fun repeatedRestartStopsPreviousListenerAndServesOnlyCurrentListener() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = WebhookListener(scope) { it.getOutputStream().write(42) }
        var previousPort: Int? = null
        try {
            repeat(100) {
                val currentPort = ServerSocket(0).use { it.localPort }
                listener.restart(InetSocketAddress("127.0.0.1", currentPort))

                previousPort?.let(::assertListenerDoesNotServe)
                Socket("127.0.0.1", currentPort).use { client ->
                    client.soTimeout = 3000
                    assertEquals(42, client.getInputStream().read())
                }
                previousPort = currentPort
            }

            listener.close()
            previousPort?.let(::assertListenerDoesNotServe)
        } finally {
            listener.close()
            scope.cancel()
        }
    }

    @Test
    fun repeatedSameAddressRestartIsIdempotent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val listener = WebhookListener(scope) { it.getOutputStream().write(42) }
        val port = ServerSocket(0).use { it.localPort }
        try {
            listener.restart(InetSocketAddress("127.0.0.1", port))
            repeat(100) {
                listener.restart(InetSocketAddress("127.0.0.1", port))
            }
            Socket("127.0.0.1", port).use { client ->
                client.soTimeout = 3000
                assertEquals(42, client.getInputStream().read())
            }
        } finally {
            listener.close()
            scope.cancel()
        }
    }

    private fun assertListenerDoesNotServe(port: Int) {
        val response = runCatching {
            Socket().use { client ->
                client.connect(InetSocketAddress("127.0.0.1", port), 300)
                client.soTimeout = 300
                client.getInputStream().read()
            }
        }.getOrDefault(-1)
        assertTrue("已替换或关闭的 Webhook 监听仍处理了请求：$port", response != 42)
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
        var firstClient: Socket? = null
        try {
            listener.restart(InetSocketAddress("127.0.0.1", firstPort))
            // Keep the first listener bound while reserving the next ephemeral port.
            // Two consecutive closed ServerSocket(0) probes may select the same port,
            // turning restart() into its documented same-address no-op.
            val secondPort = ServerSocket(0).use { it.localPort }
            assertTrue("generation restart requires distinct bound addresses", secondPort != firstPort)
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
