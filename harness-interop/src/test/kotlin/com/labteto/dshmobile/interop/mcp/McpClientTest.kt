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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {
    @Test fun optionalToolDiscoveryDoesNotHideAuthenticationOrProtocolFailure() = runTest {
        for (code in listOf(-32601, -32602, -32000)) {
            val client = McpClient(object : McpTransport {
                override suspend fun request(method: String, params: JsonObject) = buildJsonObject {
                    put("error", buildJsonObject { put("code", code) })
                }
                override fun close() = Unit
            })
            if (code == -32601) assertTrue(client.listTools(allowUnsupported = true).isEmpty())
            else {
                try {
                    client.listTools(allowUnsupported = true)
                    org.junit.Assert.fail("Only method-not-found may yield an empty tool capability")
                } catch (error: McpRpcException) { assertEquals(code, error.code) }
            }
        }
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject = throw McpHttpException(401, "unauthorized")
            override fun close() = Unit
        })
        try { client.listTools(allowUnsupported = true); org.junit.Assert.fail("Authentication must fail") }
        catch (error: McpHttpException) { assertEquals(401, error.statusCode) }
    }

    @Test
    fun resourceCapabilityProbeExposesOnlyImplementedMethods() = runTest {
        val requested = mutableListOf<String>()
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requested += method
                return when (method) {
                    "resources/list" -> buildJsonObject {
                        put("result", buildJsonObject { put("resources", buildJsonArray { }) })
                    }
                    "resources/templates/list" -> buildJsonObject {
                        put("error", buildJsonObject { put("code", -32601) })
                    }
                    else -> error("不允许探测执行性接口：$method")
                }
            }
            override fun close() = Unit
        }
        assertEquals(setOf("resources/list", "resources/read"),
            discoverMcpResourceMethods(transport))
        assertEquals(listOf("resources/list", "resources/templates/list"), requested)
    }

    @Test
    fun resourceCapabilityProbeRejectsFalsePositiveAndPreservesRealFailures() = runTest {
        val absent = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject =
                buildJsonObject { put("result", buildJsonObject { put("tools", buildJsonArray { }) }) }
            override fun close() = Unit
        }
        assertTrue(discoverMcpResourceMethods(absent).isEmpty())
        val failed = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject =
                buildJsonObject { put("error", buildJsonObject { put("code", -32000) }) }
            override fun close() = Unit
        }
        try {
            discoverMcpResourceMethods(failed)
            org.junit.Assert.fail("非方法不存在错误不能当作不支持")
        } catch (error: McpRpcException) {
            assertEquals(-32000, error.code)
        }
    }

    @Test
    fun resourceRequestsPreservePagesAndContents() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        val result = Json.parseToJsonElement("""{"nextCursor":"next/opaque+","contents":[{"uri":"test:a","blob":"YWJj","mimeType":"image/png"}],"extension":{"kept":true}}""") as JsonObject
        val client = McpClient(object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method to params
                return buildJsonObject { put("result", result) }
            }
            override fun close() = Unit
        })
        assertEquals(result, client.listResources())
        assertEquals(result, client.listResources("next/opaque+"))
        assertEquals(result, client.listResourceTemplates("template-page"))
        assertEquals(result, client.readResource("test:a"))
        assertEquals(listOf("resources/list", "resources/list", "resources/templates/list", "resources/read"), requests.map { it.first })
        assertEquals(buildJsonObject { }, requests[0].second)
        assertEquals(buildJsonObject { put("cursor", "next/opaque+") }, requests[1].second)
        assertEquals(buildJsonObject { put("cursor", "template-page") }, requests[2].second)
        assertEquals(buildJsonObject { put("uri", "test:a") }, requests[3].second)
    }

    @Test
    fun pingChecksRemoteResponseWithoutListingOrCallingTools() = runTest {
        val requests = mutableListOf<String>()
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method
                return buildJsonObject { put("result", buildJsonObject { }) }
            }
            override fun close() = Unit
        }
        McpClient(transport).ping()
        assertEquals(listOf("ping"), requests)
    }

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
    fun oversizedHttpBodyIsRejectedBeforeItCanGrowUnbounded() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://example.com/mcp").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("12345".toResponseBody())
            .build()
        response.use {
            assertThrows(IllegalStateException::class.java) {
                it.readMcpBodyBounded(maxBytes = 4)
            }
        }
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
    fun cancelledStdioRequestRequiresExplicitNewTransportBeforeAnyNextCall() = runBlocking {
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

            // The old transport must never start another process after a
            // cancellation that may have already caused remote side effects.
            val refused = runCatching {
                withTimeout(3_000L) { transport.request("tools/list") }
            }
            assertTrue(refused.isFailure)
            assertEquals("1", countFile.readText().trim())

            // Only explicit Owner/user reconnection creates another process
            // incarnation and re-discovers its tools from scratch.
            val fresh = McpLegacyStdioTransport(
                command = listOf("sh", script.absolutePath, countFile.absolutePath, markerFile.absolutePath),
                json = Json, protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
            )
            try {
                val recovered = withTimeout(3_000L) { fresh.request("tools/list") }
                assertTrue(recovered["result"] != null)
                assertEquals("2", countFile.readText().trim())
            } finally { fresh.close() }
        } finally {
            transport.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashedStdioProcessFailsClosedUntilExplicitNewTransport() = runBlocking {
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

            val refused = runCatching {
                withTimeout(3_000L) { transport.request("tools/list") }
            }
            assertTrue(refused.isFailure)
            assertEquals("1", countFile.readText().trim())
            val fresh = McpLegacyStdioTransport(
                command = listOf("sh", script.absolutePath, countFile.absolutePath),
                json = Json, protocolVersion = CURRENT_MCP_PROTOCOL_VERSION,
            )
            try {
                val recovered = withTimeout(3_000L) { fresh.request("tools/list") }
                assertTrue(recovered["result"] != null)
                assertEquals("2", countFile.readText().trim())
            } finally { fresh.close() }
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


    @Test
    fun httpJsonResponseWithWrongIdIsRejected() = runTest {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"jsonrpc":"2.0","id":999,"result":{"tools":[]}}""".toResponseBody())
                    .build()
            }
            .build()
        val transport = McpStreamableHttpTransport(
            endpoint = "https://example.com/mcp",
            http = http,
            json = Json,
        )

        val failure = runCatching { transport.request("tools/list") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("id"))
    }

}
