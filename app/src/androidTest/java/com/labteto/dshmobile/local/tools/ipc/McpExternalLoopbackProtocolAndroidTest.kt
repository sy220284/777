package com.labteto.dshmobile.local.tools.ipc

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real TCP/HTTP MCP peer on the Android loopback interface, reached through
 * the production separately bound MCP worker. The fake peer performs no remote
 * irreversible action and requires no external provider credentials.
 */
@RunWith(AndroidJUnit4::class)
class McpExternalLoopbackProtocolAndroidTest {
    @Test fun separateWorkerDiscoversAndCallsOneExternalHttpToolWithoutReplay() = runBlocking {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 20_000
        val methods = ConcurrentLinkedQueue<String>()
        val serverError = AtomicReference<Throwable?>()
        val peer = Thread({
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = BufferedReader(InputStreamReader(
                            socket.getInputStream(), Charsets.UTF_8))
                        val first = input.readLine()
                        check(first.startsWith("POST /mcp HTTP/1.")) { "Bad HTTP path: $first" }
                        var length = -1
                        while (true) {
                            val line = input.readLine() ?: error("HTTP headers truncated")
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true))
                                length = line.substringAfter(':').trim().toInt()
                        }
                        check(length in 1..65_536) { "Missing/bad Content-Length=$length" }
                        val body = CharArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            check(n > 0) { "Truncated request payload" }
                            read += n
                        }
                        val request = Json.parseToJsonElement(String(body)).jsonObject
                        val method = request.getValue("method").jsonPrimitive.content
                        val id = request.getValue("id")
                        methods.add(method)
                        val result = when (method) {
                            "tools/list" -> """{"tools":[{"name":"echo","description":"loopback fixture","inputSchema":{"type":"object"}}]}"""
                            "tools/call" -> {
                                check(request["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "echo")
                                """{"content":[{"type":"text","text":"ok"}],"isError":false}"""
                            }
                            else -> error("Unexpected MCP method $method")
                        }
                        val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                        val response = json.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().write(response)
                        socket.getOutputStream().flush()
                    }
                }
            } catch (failure: Throwable) {
                serverError.set(failure)
            }
        }, "mcp-loopback-peer").apply { isDaemon = true; start() }

        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val transport = McpPluginWorkerTransport(context, kind = "http",
            endpoint = "http://127.0.0.1:${server.localPort}/mcp")
        try {
            val listed = withTimeout(20_000) {
                transport.request("tools/list", JsonObject(emptyMap()))
            }
            assertTrue(listed["result"].toString().contains("echo"))
            assertNotEquals(Process.myPid(), transport.workerPid)
            val called = withTimeout(20_000) {
                transport.request("tools/call", buildJsonObject {
                    put("name", "echo")
                    put("arguments", buildJsonObject { put("text", "once") })
                })
            }
            assertTrue(called["result"].toString().contains("ok"))
        } finally {
            transport.close()
            peer.join(3_000)
            server.close()
        }
        assertNull("MCP TCP peer failed", serverError.get())
        assertEquals("one discovery, one tool call; no hidden replay",
            listOf("tools/list", "tools/call"), methods.toList())
    }
}
