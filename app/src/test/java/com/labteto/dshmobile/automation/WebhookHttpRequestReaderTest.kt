package com.labteto.dshmobile.automation

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebhookHttpRequestReaderTest {
    private fun withSocketPair(block: (Socket, Socket) -> Unit) {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { client ->
                server.accept().use { accepted -> block(client, accepted) }
            }
        }
    }

    @Test fun headCanBeAuthenticatedBeforeBodyIsRead() = withSocketPair { client, accepted ->
        client.getOutputStream().write(
            "POST /run HTTP/1.1\r\nAuthorization: Bearer secret\r\nContent-Length: 5\r\n\r\n".toByteArray(),
        )
        val reader = WebhookHttpRequestReader(accepted, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        val head = reader.readHead(16 * 1024)!!
        assertEquals("POST", head.method)
        assertEquals("Bearer secret", head.headers["authorization"])
        client.getOutputStream().write("hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), reader.readBody(5))
    }

    @Test fun silentClientCannotHoldTheHeaderReadIndefinitely() = withSocketPair { _, accepted ->
        val reader = WebhookHttpRequestReader(accepted, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(70))
        assertThrows(SocketTimeoutException::class.java) { reader.readHead(16 * 1024) }
    }

    @Test fun bodyUsesTheSameDeadlineAsHeaders() = withSocketPair { client, accepted ->
        client.getOutputStream().write("POST /run HTTP/1.1\r\nContent-Length: 4\r\n\r\n".toByteArray())
        val reader = WebhookHttpRequestReader(accepted, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(90))
        assertEquals("POST", reader.readHead(16 * 1024)?.method)
        assertThrows(SocketTimeoutException::class.java) { reader.readBody(4) }
    }

    @Test fun conflictingContentLengthsAreRejected() = withSocketPair { client, accepted ->
        client.getOutputStream().write("POST /run HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n".toByteArray())
        val reader = WebhookHttpRequestReader(accepted, System.nanoTime() + TimeUnit.SECONDS.toNanos(1))
        assertTrue(reader.readHead(16 * 1024) == null)
    }
}
