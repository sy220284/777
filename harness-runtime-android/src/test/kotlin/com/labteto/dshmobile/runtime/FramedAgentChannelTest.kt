package com.labteto.dshmobile.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramedAgentChannelTest {
    @Test fun multiMegabytePayloadRoundTripsWithoutBinderOrLineTruncation() = runBlocking {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { client ->
                listener.accept().use { server ->
                    val host = FramedAgentChannel(client.inputStream, client.outputStream, onClose = client::close)
                    val peer = FramedAgentChannel(server.inputStream, server.outputStream, onClose = server::close)
                    try {
                        val value = buildJsonObject { put("history", "x".repeat(2 * 1024 * 1024)) }
                        val sent = async(Dispatchers.IO) { host.send(value) }
                        assertEquals(value, withTimeout(10_000) { peer.receive() })
                        sent.await()
                    } finally { host.close(); peer.close() }
                }
            }
        }
    }
    @Test fun cancellationUnblocksNativeReadAndClosesTransport() = runBlocking {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { client ->
                listener.accept().use { server ->
                    val channel = FramedAgentChannel(client.inputStream, client.outputStream, onClose = client::close)
                    val entered = CompletableDeferred<Unit>()
                    val reading = async(Dispatchers.IO) { entered.complete(Unit); channel.receive() }
                    withTimeout(5000) { entered.await(); reading.cancelAndJoin() }
                    assertTrue(client.isClosed)
                    assertTrue(reading.isCancelled)
                    channel.close()
                    server.soTimeout = 5000
                    assertEquals(-1, server.inputStream.read())
                }
            }
        }
    }
    @Test fun invalidOrTruncatedLengthCannotBeAcceptedAsAFrame() = runBlocking {
        for ((length, payload) in listOf(-1 to byteArrayOf(), Int.MAX_VALUE to byteArrayOf(), 20 to "{}".toByteArray())) {
            val encoded = ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeInt(length); write(payload) } }
            val channel = FramedAgentChannel(ByteArrayInputStream(encoded.toByteArray()), ByteArrayOutputStream())
            try { assertTrue(runCatching { channel.receive() }.isFailure) } finally { channel.close() }
        }
    }
    @Test fun peerNestingIsRejectedBeforeRecursiveDeserialization() = runBlocking {
        val bytes = ("{\"nested\":".repeat(300) + "0" + "}".repeat(300)).toByteArray()
        val encoded = ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeInt(bytes.size); write(bytes) } }
        val channel = FramedAgentChannel(ByteArrayInputStream(encoded.toByteArray()), ByteArrayOutputStream())
        try {
            assertTrue(runCatching { channel.receive() }.exceptionOrNull()?.message.orEmpty().contains("JSON_DEPTH_INVALID"))
        } finally { channel.close() }
    }

}
