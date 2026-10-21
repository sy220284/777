package com.labteto.dshmobile.local.tools

import java.net.Socket
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The browser callback is not consumed by unrelated local requests or favicon probes. */
class LocalMcpOAuthCallbackAndroidTest {
    @Test fun unrelatedLoopbackRequestCannotStealTheOneTimeAuthorizationState() = runBlocking {
        LocalMcpOAuthCoordinator.CallbackListener().use { callback ->
            val received = async(Dispatchers.IO) {
                withTimeout(5_000L) { callback.await("correct-state") }
            }
            val address = URI(callback.redirectUri)
            suspend fun request(path: String): String = withContext(Dispatchers.IO) {
                Socket("127.0.0.1", address.port).use { socket ->
                    socket.soTimeout = 3_000
                    socket.getOutputStream().write(
                        ("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                    socket.getInputStream().bufferedReader().readLine()
                }
            }
            assertTrue(request("/favicon.ico").contains("400"))
            assertTrue(request("/mcp-callback?state=wrong&code=interloper").contains("400"))
            assertTrue(request("/mcp-callback?state=correct-state&code=ok").contains("200"))
            assertEquals("ok", received.await()["code"])
        }
    }
}
