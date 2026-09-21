package com.labteto.dshmobile.interop.mcp

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    @Test
    fun listsAndCallsToolsThroughTransport() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method to params
                return when (method) {
                    "tools/list" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo")
                                    put("description", "echo")
                                    put("inputSchema", buildJsonObject {
                                        put("type", "object")
                                    })
                                })
                            })
                        })
                    }
                    "tools/call" -> buildJsonObject {
                        put("result", buildJsonObject { put("resultType", "complete") })
                    }
                    else -> error("unexpected method")
                }
            }

            override fun close() = Unit
        }

        val client = McpClient(transport)
        val tools = client.listTools()
        assertEquals("echo", tools.single().name)
        client.callTool("echo", buildJsonObject { put("text", "hi") })
        assertEquals(listOf("tools/list", "tools/call"), requests.map { it.first })
        assertEquals("echo", requests.last().second["name"].toString().trim('"'))
    }

    @Test
    fun headerEncodingPreservesAsciiAndProtectsUnicode() {
        assertEquals("plain", encodeHeaderValue("plain"))
        val encoded = encodeHeaderValue("中文")
        assertTrue(encoded.startsWith("base64:"))
    }

    @Test
    fun cancellingHttpRequestCancelsUnderlyingCallPromptly() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }

        val transport = McpStreamableHttpTransport(
            endpoint = "http://127.0.0.1:${server.localPort}/mcp",
            http = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            json = Json,
        )
        try {
            val job = launch(Dispatchers.Default) {
                transport.request("tools/list")
            }
            assertTrue("test server did not receive MCP request", entered.await(3, TimeUnit.SECONDS))
            withTimeout(2_000L) {
                job.cancelAndJoin()
            }
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            transport.close()
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun cancelledStdioRequestRestartsAndReinitializesBeforeNextCall() = runBlocking {
        val dir = Files.createTempDirectory("mcp-cancel-").toFile()
        val countFile = File(dir, "count")
        val markerFile = File(dir, "blocked")
        val script = File(dir, "server.sh")
        script.writeText(
            """
            count_file="§1"
            marker_file="§2"
            run=§(cat "§count_file" 2>/dev/null || printf '0')
            run=§((run + 1))
            printf '%s' "§run" > "§count_file"
            initialized=0
            while IFS= read -r line; do
              case "§line" in
                *'"method":"initialize"'*)
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2026-07-28"}}\n' "§id"
                  ;;
                *'"method":"notifications/initialized"'*)
                  initialized=1
                  ;;
                *'"method":"tools/list"'*)
                  [ "§initialized" -eq 1 ] || exit 9
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  if [ "§run" -eq 1 ]; then
                    : > "§marker_file"
                    while IFS= read -r ignored; do :; done
                    exit 0
                  fi
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[]}}\n' "§id"
                  ;;
              esac
            done
            """.trimIndent().replace('§', '$'),
        )

        val transport = McpLegacyStdioTransport(
            command = listOf("sh", script.absolutePath, countFile.absolutePath, markerFile.absolutePath),
            json = Json,
            protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
        )
        try {
            val blocked = launch(Dispatchers.Default) {
                transport.request("tools/list")
            }
            withTimeout(3_000L) {
                while (!markerFile.isFile) delay(10)
            }
            withTimeout(2_000L) {
                blocked.cancelAndJoin()
            }
            assertTrue(blocked.isCancelled)

            val recovered = withTimeout(3_000L) {
                transport.request("tools/list")
            }
            assertTrue(recovered["result"] != null)
            assertEquals("2", countFile.readText().trim())
        } finally {
            transport.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashedStdioProcessIsReinitializedOnRestart() = runBlocking {
        val dir = Files.createTempDirectory("mcp-restart-").toFile()
        val countFile = File(dir, "count")
        val script = File(dir, "server.sh")
        script.writeText(
            """
            count_file="§1"
            run=§(cat "§count_file" 2>/dev/null || printf '0')
            run=§((run + 1))
            printf '%s' "§run" > "§count_file"
            initialized=0
            while IFS= read -r line; do
              case "§line" in
                *'"method":"initialize"'*)
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2026-07-28"}}\n' "§id"
                  ;;
                *'"method":"notifications/initialized"'*)
                  initialized=1
                  ;;
                *'"method":"tools/list"'*)
                  [ "§initialized" -eq 1 ] || exit 9
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  if [ "§run" -eq 1 ]; then
                    exit 17
                  fi
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[]}}\n' "§id"
                  ;;
              esac
            done
            """.trimIndent().replace('§', '$'),
        )

        val transport = McpLegacyStdioTransport(
            command = listOf("sh", script.absolutePath, countFile.absolutePath),
            json = Json,
            protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
        )
        try {
            val first = runCatching {
                withTimeout(3_000L) { transport.request("tools/list") }
            }
            assertTrue(first.isFailure)

            val recovered = withTimeout(3_000L) {
                transport.request("tools/list")
            }
            assertTrue(recovered["result"] != null)
            assertEquals("2", countFile.readText().trim())
        } finally {
            transport.close()
            dir.deleteRecursively()
        }
    }


    @Test
    fun stdioProcessUsesResolvedCommandAndSharedEnvironment() = runBlocking {
        val dir = Files.createTempDirectory("mcp-shared-env-").toFile()
        val script = File(dir, "server.sh")
        script.writeText(
            """
            [ "§DSH_RUNTIME_FLAG" = "ready" ] || exit 21
            initialized=0
            while IFS= read -r line; do
              case "§line" in
                *'"method":"initialize"'*)
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2026-07-28"}}\n' "§id"
                  ;;
                *'"method":"notifications/initialized"'*)
                  initialized=1
                  ;;
                *'"method":"tools/list"'*)
                  [ "§initialized" -eq 1 ] || exit 22
                  id=§(printf '%s\n' "§line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
                  printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[]}}\n' "§id"
                  ;;
              esac
            done
            """.trimIndent().replace('§', '$'),
        )

        var resolved = false
        val transport = McpLegacyStdioTransport(
            command = listOf("bundled-node", "ignored"),
            json = Json,
            protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
            commandResolver = {
                resolved = true
                listOf("sh", script.absolutePath)
            },
            environmentProvider = { mapOf("DSH_RUNTIME_FLAG" to "ready") },
        )
        try {
            val response = withTimeout(3_000L) {
                transport.request("tools/list")
            }
            assertTrue(resolved)
            assertTrue(response["result"] != null)
        } finally {
            transport.close()
            dir.deleteRecursively()
        }
    }

}
