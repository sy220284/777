package com.labteto.dshmobile.interop.mcp

import java.io.File
import com.labteto.dshmobile.harness.plugin.PluginRegistry
import com.labteto.dshmobile.harness.tools.ToolContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolBridgePluginTest {
    @Test fun resourceOnlyAndFullToolCapacityUseOneConnection() = runBlocking {
        for (toolCount in listOf(0, 128)) {
            var connections = 0
            val methods = mutableListOf<String>()
            val plugin = McpToolBridgePlugin(http = OkHttpClient(), json = Json, transportFactory = {
                connections++
                object : McpTransport, McpResourceDiscovery {
                    override suspend fun supportedResourceMethods() =
                        setOf("resources/list", "resources/templates/list", "resources/read")
                    override suspend fun request(method: String, params: JsonObject): JsonObject {
                        methods += method
                        if (method == "tools/list" && toolCount == 0) return buildJsonObject {
                            put("error", buildJsonObject { put("code", -32601); put("message", "Method not found") })
                        }
                        return buildJsonObject { put("result", buildJsonObject {
                            if (method == "tools/list") put("tools", buildJsonArray {
                                repeat(toolCount) { index -> add(buildJsonObject {
                                    put("name", if (index == 0) "resources_list" else "tool_$index")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                }) }
                            }) else put("nextCursor", "page2")
                        }) }
                    }
                    override fun close() = Unit
                }
            })
            val registry = PluginRegistry()
            registry.install(plugin)
            plugin.connectHttpFromUi(registry.context, "resources", "https://example.com/mcp")
            assertEquals(toolCount + 3, plugin.serverSnapshots().single().tools.size)
            val resourceName = "mcp_resources_resources_list" + if (toolCount == 0) "" else "_1"
            val result = registry.context.tools.execute(resourceName, buildJsonObject { put("cursor", "opaque-page") },
                context = ToolContext(approval = { true }))
            assertFalse(result.isError)
            assertEquals("resources/list", methods.last())
            assertTrue(result.richContent != null)
            assertEquals(1, connections)
            plugin.uninstall(registry.context)
        }
    }

    @Test fun toolOnlyServerDoesNotExposeUnimplementedResourceMethods() = runBlocking {
        val plugin = McpToolBridgePlugin(http = OkHttpClient(), json = Json, transportFactory = {
            object : McpTransport {
                override suspend fun request(method: String, params: JsonObject): JsonObject =
                    if (method == "tools/list") buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                })
                            })
                        })
                    } else error("不允许向仅支持工具的 MCP 服务调用 $method")
                override fun close() = Unit
            }
        })
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            plugin.connectHttpFromUi(registry.context, "tools_only", "https://example.com/mcp")
            assertEquals(listOf("mcp_tools_only_echo"), plugin.serverSnapshots().single().tools)
            assertNull(registry.context.tools.get("mcp_tools_only_resources_list"))
            assertNull(registry.context.tools.get("mcp_tools_only_resources_read"))
        } finally {
            plugin.uninstall(registry.context)
        }
    }

    @Test fun resourceToolsShareApprovalBindingAndDisconnectCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<String>()
        var resourceCalls = 0
        val plugin = McpToolBridgePlugin(http = OkHttpClient(), json = Json, transportFactory = {
            object : McpTransport, McpResourceDiscovery {
                override suspend fun supportedResourceMethods() =
                    setOf("resources/list", "resources/templates/list", "resources/read")
                override suspend fun request(method: String, params: JsonObject): JsonObject {
                    if (method == "tools/list") return buildJsonObject {
                        put("result", buildJsonObject { put("tools", buildJsonArray { }) })
                    }
                    assertEquals("resources/read", method)
                    assertEquals("test:image", params["uri"]?.jsonPrimitive?.content)
                    resourceCalls++
                    entered.complete(Unit)
                    try { awaitCancellation() } catch (error: kotlinx.coroutines.CancellationException) {
                        cancelled.complete(error.message.orEmpty())
                        throw error
                    }
                }
                override fun close() = Unit
            }
        })
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "resource", "https://example.com/mcp")
        val name = "mcp_resource_resources_read"
        val input = buildJsonObject { put("uri", "test:image") }
        assertTrue(registry.context.tools.execute(name, input).isError)
        assertEquals(0, resourceCalls)
        val call = async { registry.context.tools.execute(name, input, context = ToolContext(approval = { true })) }
        entered.await()
        withTimeout(2_000L) {
            plugin.disconnectFromUi(registry.context, "resource")
            assertEquals("MCP 连接所有者取消在途调用：resource", cancelled.await())
            call.join()
        }
        assertTrue(call.isCancelled)
        assertNull(registry.context.tools.get(name))
        assertEquals(1, resourceCalls)
    }

    @Test fun agentSubscriptionAndLifecycleToolUseTheSameOrderedOwnerCursor() = runBlocking {
        val plugin = McpToolBridgePlugin(http = OkHttpClient(), json = Json,
            transportFactory = {
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject) = buildJsonObject {
                        put("result", buildJsonObject { put("tools", buildJsonArray { }) })
                    }
                    override fun close() = Unit
                }
            })
        val registry = PluginRegistry()
        registry.install(plugin)
        try {
            val observer = async(start = CoroutineStart.UNDISPATCHED) {
                plugin.observeLifecycle().first { page ->
                    page.events.any { it.snapshot?.lifecycle == McpServerLifecycle.REGISTERED }
                }
            }
            plugin.connectHttpFromUi(registry.context, "observed", "https://example.com/mcp")
            val event = withTimeout(5_000) { observer.await() }
            assertTrue(event.events.any { it.serverId == "observed" })
            val result = registry.context.tools.execute("mcp_lifecycle_events", buildJsonObject {
                put("incarnation", "retired-owner"); put("after_sequence", event.latestSequence)
            })
            val payload = Json.parseToJsonElement(result.content).jsonObject
            assertEquals("true", payload["requires_snapshot"]?.jsonPrimitive?.content)
            assertEquals(event.incarnation, payload["incarnation"]?.jsonPrimitive?.content)
            assertTrue(payload.containsKey("servers"))
        } finally { registry.uninstall("mcp-bridge") }
    }

    @Test
    fun unsolicitedTransportExitRevokesOnlyThatServerWithoutReplayingItsTool() = runBlocking {
        val termination = MutableSharedFlow<Throwable>(replay = 1)
        var remoteCalls = 0
        var closed = 0
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(), json = Json,
            transportFactory = { endpoint ->
                object : McpTransport, McpTerminationSource {
                    override val terminalEvents = termination
                    override suspend fun request(method: String, params: JsonObject): JsonObject = when (method) {
                        "tools/list" -> buildJsonObject {
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray {
                                    add(buildJsonObject {
                                        put("name", "write")
                                        put("inputSchema", buildJsonObject { put("type", "object") })
                                    })
                                })
                            })
                        }
                        "tools/call" -> {
                            remoteCalls++
                            buildJsonObject { put("result", buildJsonObject { put("isError", false) }) }
                        }
                        else -> error("unexpected call: $method")
                    }
                    override fun close() { closed++ }
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "server", "https://example.com/mcp")
        val registered = plugin.serverSnapshots().single().tools
        assertEquals(1, registered.size)
        assertTrue(registered.all { registry.context.tools.get(it) != null })
        val revision = plugin.lifecycleRevisions.value
        termination.emit(java.io.IOException("stream ended without a request"))
        withTimeout(5_000) {
            while (plugin.lifecycleSnapshots().singleOrNull()?.lifecycle != McpServerLifecycle.FAILED) delay(10)
        }
        assertTrue(plugin.lifecycleRevisions.value > revision)
        registered.forEach { assertNull(registry.context.tools.get(it)) }
        assertEquals(McpServerLifecycle.FAILED, plugin.lifecycleSnapshots().single().lifecycle)
        val changes = plugin.lifecycleEventsSince(null, -1L)
        assertEquals(listOf(
            McpServerLifecycle.CONNECTING,
            McpServerLifecycle.REGISTERED,
            McpServerLifecycle.FAILED,
        ), changes.events.map { it.snapshot?.lifecycle })
        assertTrue(changes.events.zipWithNext().all { (before, after) ->
            before.sequence < after.sequence && before.serverId == "server" && after.serverId == "server"
        })
        assertTrue(plugin.lifecycleEventsSince("previous-process", changes.latestSequence)
            .requiresSnapshot)
        assertEquals(0, remoteCalls)
        assertEquals(1, closed)
        plugin.uninstall(registry.context)
    }

    @Test
    fun explicitHealthPingDoesNotExecuteToolsOrClaimRemoteAuthorization() = runTest {
        val requests = mutableListOf<String>()
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = {
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject {
                        requests += method
                        return when (method) {
                            "tools/list" -> buildJsonObject {
                                put("result", buildJsonObject { put("tools", buildJsonArray { }) })
                            }
                            "ping" -> buildJsonObject { put("result", buildJsonObject { }) }
                            else -> error("Health must never call $method")
                        }
                    }
                    override fun close() = Unit
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        assertEquals(McpServerHealth.NOT_CONNECTED, plugin.checkServerHealthFromUi("health"))
        plugin.connectHttpFromUi(registry.context, "health", "https://example.com/mcp")
        assertEquals(McpServerHealth.RESPONDED, plugin.checkServerHealthFromUi("health"))
        assertEquals(listOf("tools/list", "ping"), requests)
        plugin.uninstall(registry.context)
    }

    @Test
    fun rejectedHealthProbeIsUnconfirmedNotAFalseAuthorizationClaim() = runTest {
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = {
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject =
                        if (method == "tools/list") buildJsonObject {
                            put("result", buildJsonObject { put("tools", buildJsonArray { }) })
                        } else throw java.io.IOException("health endpoint unreachable")
                    override fun close() = Unit
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "unconfirmed", "https://example.com/mcp")
        assertEquals(McpServerHealth.UNCONFIRMED,
            plugin.checkServerHealthFromUi("unconfirmed"))
        plugin.uninstall(registry.context)
    }

    @Test
    fun largeToolResultPreservesMiddleBeforeDurableProjection() {
        val result = buildJsonObject {
            put("content", "x".repeat(300_000) + "MIDDLE_SENTINEL" + "y".repeat(300_000))
        }

        val bounded = completeMcpToolResult(result)

        assertEquals(result.toString(), bounded)
        assertTrue(bounded.contains("MIDDLE_SENTINEL"))
    }

    @Test
    fun concurrentReconnectCannotStealOrDisconnectAnInProgressReconnect() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var connections = 0
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = {
                val generation = ++connections
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject {
                        require(method == "tools/list")
                        if (generation == 2) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return buildJsonObject {
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray { })
                            })
                        }
                    }
                    override fun close() = Unit
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "guarded", "https://example.com/mcp")
        val running = async {
            registry.context.tools.execute(
                "mcp_reconnect",
                buildJsonObject { put("server_id", "guarded") },
                context = ToolContext(approval = { true }),
            )
        }
        entered.await()
        val duplicate = registry.context.tools.execute(
            "mcp_reconnect",
            buildJsonObject { put("server_id", "guarded") },
            context = ToolContext(approval = { true }),
        )
        val disconnect = registry.context.tools.execute(
            "mcp_disconnect",
            buildJsonObject { put("server_id", "guarded") },
            context = ToolContext(approval = { true }),
        )
        assertEquals("MCP_CONNECTION_BUSY", duplicate.errorCode)
        assertEquals("MCP_CONNECTION_BUSY", disconnect.errorCode)
        assertEquals(2, connections)
        assertEquals(
            listOf("guarded" to McpServerLifecycle.RECONNECTING),
            plugin.lifecycleSnapshots().map { it.id to it.lifecycle },
        )
        release.complete(Unit)
        assertFalse(running.await().isError)
        assertEquals(listOf("guarded"), plugin.serverSnapshots().map { it.id })
        plugin.uninstall(registry.context)
    }

    @Test
    fun failedRemoteRequestRequiresExplicitReconnectAndNeverReplaysTheRequest() = runTest {
        var connections = 0
        var remoteCalls = 0
        var oldClosed = false
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = {
                val generation = ++connections
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject =
                        when (method) {
                            "tools/list" -> buildJsonObject {
                                put("result", buildJsonObject {
                                    put("tools", buildJsonArray {
                                        add(buildJsonObject {
                                            put("name", "write")
                                            put("inputSchema", buildJsonObject { put("type", "object") })
                                        })
                                    })
                                })
                            }
                            "tools/call" -> {
                                remoteCalls++
                                if (generation == 1) throw java.io.IOException("remote disconnected")
                                buildJsonObject {
                                    put("result", buildJsonObject { put("isError", false) })
                                }
                            }
                            else -> error("unexpected request")
                        }
                    override fun close() {
                        if (generation == 1) oldClosed = true
                    }
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "broken", "https://example.com/mcp")
        val failed = registry.context.tools.execute(
            "mcp_broken_write", buildJsonObject { },
            context = ToolContext(approval = { true }),
        )
        assertTrue(failed.isError)
        assertEquals("MCP_TRANSPORT_FAILED", failed.errorCode)
        assertFalse(failed.retryable)
        assertEquals(1, remoteCalls)
        assertEquals(1, connections)
        assertEquals(
            listOf("broken" to McpServerLifecycle.FAILED),
            plugin.lifecycleSnapshots().map { it.id to it.lifecycle },
        )
        assertNull(registry.context.tools.get("mcp_broken_write"))
        assertEquals(McpServerHealth.NOT_CONNECTED, plugin.checkServerHealthFromUi("broken"))

        val reconnected = registry.context.tools.execute(
            "mcp_reconnect",
            buildJsonObject { put("server_id", "broken") },
            context = ToolContext(approval = { true }),
        )
        assertFalse(reconnected.isError)
        assertTrue(oldClosed)
        assertEquals(2, connections)
        assertEquals(1, remoteCalls)
        assertEquals(McpServerLifecycle.REGISTERED, plugin.lifecycleSnapshots().single().lifecycle)
        val recovered = registry.context.tools.execute(
            "mcp_broken_write", buildJsonObject { },
            context = ToolContext(approval = { true }),
        )
        assertFalse(recovered.isError)
        assertEquals(2, remoteCalls)
        plugin.uninstall(registry.context)
    }

    @Test
    fun connectDiscoversRegistersCallsAndDisconnectsRemoteTools() = runTest {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var closed = false
        var capturedEndpoint: String? = null
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                requests += method to params
                return when (method) {
                    "tools/list" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "echo.tool")
                                    put("description", "Echo remote input")
                                    put("inputSchema", buildJsonObject {
                                        put("type", "object")
                                        put("properties", buildJsonObject {
                                            put("text", buildJsonObject { put("type", "string") })
                                        })
                                    })
                                })
                            })
                        })
                    }
                    "tools/call" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("isError", false)
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "remote-ok")
                                })
                            })
                        })
                    }
                    else -> error("unexpected method $method")
                }
            }

            override fun close() {
                closed = true
            }
        }
        val registry = PluginRegistry()
        registry.install(
            McpToolBridgePlugin(
                http = OkHttpClient(),
                json = Json,
                transportFactory = { endpoint ->
                    capturedEndpoint = endpoint
                    transport
                },
            ),
        )

        val connectInput = buildJsonObject {
            put("server_id", "demo")
            put("endpoint", "https://example.com/mcp?token=secret")
        }
        val blockedConnect = registry.context.tools.execute("mcp_http_connect", connectInput)
        assertTrue(blockedConnect.isError)

        val connect = registry.context.tools.execute(
            "mcp_http_connect",
            connectInput,
            context = ToolContext(approval = { true }),
        )
        assertFalse(connect.isError)
        assertEquals("https://example.com/mcp?token=secret", capturedEndpoint)

        val localName = "mcp_demo_echo_tool"
        assertTrue(registry.context.tools.names().contains(localName))

        val blockedCall = registry.context.tools.execute(
            localName,
            buildJsonObject { put("text", "hello") },
        )
        assertTrue(blockedCall.isError)

        val call = registry.context.tools.execute(
            localName,
            buildJsonObject { put("text", "hello") },
            context = ToolContext(approval = { true }),
        )
        assertFalse(call.isError)
        assertTrue(call.content.contains("remote-ok"))
        assertEquals("echo.tool", requests.last().second["name"]?.jsonPrimitive?.content)
        assertEquals("hello", requests.last().second["arguments"]?.let { it as JsonObject }?.get("text")?.jsonPrimitive?.content)

        val listing = registry.context.tools.execute("mcp_server_list", buildJsonObject { })
        assertTrue(listing.content.contains("https://example.com/mcp"))
        assertFalse(listing.content.contains("secret"))

        val disconnected = registry.context.tools.execute(
            "mcp_disconnect",
            buildJsonObject { put("server_id", "demo") },
            context = ToolContext(approval = { true }),
        )
        assertFalse(disconnected.isError)
        assertNull(registry.context.tools.get(localName))
        assertTrue(closed)
    }

    @Test
    fun disconnectWaitsForInflightCallBeforeClosingTransport() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<String>()
        var closed = false
        val transport = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject =
                when (method) {
                    "tools/list" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("tools", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "slow")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                })
                            })
                        })
                    }
                    "tools/call" -> {
                        entered.complete(Unit)
                        try { awaitCancellation() } catch (error: kotlinx.coroutines.CancellationException) {
                            cancelled.complete(error.message.orEmpty())
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                            throw error
                        }
                    }
                    else -> error("unexpected method $method")
                }

            override fun close() {
                closed = true
            }
        }
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = { transport },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "slow-server", "https://example.com/mcp")

        val call = async {
            registry.context.tools.execute(
                "mcp_slow-server_slow",
                buildJsonObject { },
                context = ToolContext(approval = { true }),
            )
        }
        entered.await()

        val disconnect = async {
            plugin.disconnectFromUi(registry.context, "slow-server")
        }
        withTimeout(2_000L) {
            assertEquals("MCP 连接所有者取消在途调用：slow-server", cancelled.await())
        }

        assertFalse(disconnect.isCompleted)
        assertFalse(closed)
        assertEquals(
            listOf("slow-server" to McpServerLifecycle.DISCONNECTING),
            plugin.lifecycleSnapshots().map { it.id to it.lifecycle },
        )

        release.complete(Unit)
        withTimeout(2_000L) {
            call.join()
            assertTrue(disconnect.await().contains("已断开"))
        }
        assertTrue(call.isCancelled)
        assertTrue(closed)
        assertNull(registry.context.tools.get("mcp_slow-server_slow"))
    }

    @Test
    fun slowConnectDoesNotBlockExistingServerSnapshots() = runTest {
        val slowEntered = CompletableDeferred<Unit>()
        val slowRelease = CompletableDeferred<Unit>()
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = { endpoint ->
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject {
                        require(method == "tools/list")
                        if (endpoint.contains("slow")) {
                            slowEntered.complete(Unit)
                            slowRelease.await()
                        }
                        return buildJsonObject {
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray { })
                            })
                        }
                    }

                    override fun close() = Unit
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)
        plugin.connectHttpFromUi(registry.context, "fast", "https://example.com/fast")
        val revisionAfterFast = plugin.lifecycleRevisions.value

        val slow = async {
            plugin.connectHttpFromUi(registry.context, "slow", "https://example.com/slow")
        }
        slowEntered.await()
        val revisionConnecting = plugin.lifecycleRevisions.value
        assertTrue(revisionConnecting > revisionAfterFast)

        val snapshots = plugin.serverSnapshots()
        assertEquals(listOf("fast"), snapshots.map(McpServerSnapshot::id))
        assertEquals(
            listOf("fast" to McpServerLifecycle.REGISTERED, "slow" to McpServerLifecycle.CONNECTING),
            plugin.lifecycleSnapshots().map { it.id to it.lifecycle },
        )
        assertFalse(slow.isCompleted)

        slowRelease.complete(Unit)
        assertTrue(slow.await().contains("已连接"))
        assertTrue(plugin.lifecycleRevisions.value > revisionConnecting)
        assertEquals(McpServerLifecycle.REGISTERED, plugin.lifecycleSnapshots().last().lifecycle)
        val revisionRegistered = plugin.lifecycleRevisions.value
        plugin.disconnectFromUi(registry.context, "slow")
        assertTrue(plugin.lifecycleRevisions.value > revisionRegistered)
        assertEquals(listOf("fast"), plugin.lifecycleSnapshots().map(McpServerSnapshot::id))
    }

    @Test
    fun cancelledConnectReleasesServerReservation() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        var attempts = 0
        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = {
                val attempt = ++attempts
                object : McpTransport {
                    override suspend fun request(method: String, params: JsonObject): JsonObject {
                        require(method == "tools/list")
                        if (attempt == 1) {
                            firstEntered.complete(Unit)
                            awaitCancellation()
                        }
                        return buildJsonObject {
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray { })
                            })
                        }
                    }

                    override fun close() = Unit
                }
            },
        )
        val registry = PluginRegistry()
        registry.install(plugin)

        val first = async {
            plugin.connectHttpFromUi(registry.context, "retry", "https://example.com/mcp")
        }
        firstEntered.await()
        first.cancelAndJoin()

        val retry = plugin.connectHttpFromUi(registry.context, "retry", "https://example.com/mcp")
        assertTrue(retry.contains("已连接"))
        assertEquals(2, attempts)
    }

    @Test
    fun connectRejectsCredentialsEmbeddedInEndpoint() = runTest {
        val registry = PluginRegistry()
        registry.install(
            McpToolBridgePlugin(
                http = OkHttpClient(),
                json = Json,
                transportFactory = { error("transport should not be created") },
            ),
        )

        val result = registry.context.tools.execute(
            "mcp_http_connect",
            buildJsonObject {
                put("server_id", "demo")
                put("endpoint", "https://user:pass@example.com/mcp")
            },
            context = ToolContext(approval = { true }),
        )
        assertTrue(result.isError)
        assertEquals("MCP_CONNECT_FAILED", result.errorCode)
        assertFalse(result.content.contains("user:pass"))
    }

    @Test
    fun stdioConnectUsesApprovedCommandAndKeepsWorkingDirectoryInsideWorkspace() = runTest {
        val root = kotlin.io.path.createTempDirectory("mcp-stdio-root-").toFile()
        val outside = kotlin.io.path.createTempDirectory("mcp-stdio-outside-").toFile()
        try {
            var capturedCommand: List<String>? = null
            var capturedDirectory: File? = null
            var closed = false
            val transport = object : McpTransport {
                override suspend fun request(method: String, params: JsonObject): JsonObject =
                    when (method) {
                        "tools/list" -> buildJsonObject {
                            put("result", buildJsonObject {
                                put("tools", buildJsonArray {
                                    add(buildJsonObject {
                                        put("name", "ping")
                                        put("description", "Ping")
                                        put("inputSchema", buildJsonObject { put("type", "object") })
                                    })
                                })
                            })
                        }
                        "tools/call" -> buildJsonObject {
                            put("result", buildJsonObject {
                                put("isError", false)
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", "pong")
                                    })
                                })
                            })
                        }
                        else -> error("unexpected method $method")
                    }

                override fun close() {
                    closed = true
                }
            }

            val registry = PluginRegistry()
            registry.install(
                McpToolBridgePlugin(
                    http = OkHttpClient(),
                    json = Json,
                    workspaceRoot = root,
                    stdioTransportFactory = { command, directory ->
                        capturedCommand = command
                        capturedDirectory = directory
                        transport
                    },
                ),
            )

            val input = buildJsonObject {
                put("server_id", "local")
                put("command", buildJsonArray {
                    add(JsonPrimitive("node"))
                    add(JsonPrimitive("server.js"))
                })
                put("working_directory", root.absolutePath)
            }

            assertTrue(registry.context.tools.execute("mcp_stdio_connect", input).isError)

            val connected = registry.context.tools.execute(
                "mcp_stdio_connect",
                input,
                context = ToolContext(approval = { true }),
            )
            assertFalse(connected.isError)
            assertEquals(listOf("node", "server.js"), capturedCommand)
            assertEquals(root.canonicalFile, capturedDirectory)
            assertTrue(registry.context.tools.names().contains("mcp_local_ping"))

            val listing = registry.context.tools.execute("mcp_server_list", buildJsonObject { })
            assertTrue(listing.content.contains("\"transport\":\"stdio\""))
            assertTrue(listing.content.contains("stdio:node"))
            assertFalse(listing.content.contains("server.js"))

            val outsideAttempt = registry.context.tools.execute(
                "mcp_stdio_connect",
                buildJsonObject {
                    put("server_id", "outside")
                    put("command", buildJsonArray { add(JsonPrimitive("node")) })
                    put("working_directory", outside.absolutePath)
                },
                context = ToolContext(approval = { true }),
            )
            assertTrue(outsideAttempt.isError)
            assertEquals("MCP_CONNECT_FAILED", outsideAttempt.errorCode)

            val disconnected = registry.context.tools.execute(
                "mcp_disconnect",
                buildJsonObject { put("server_id", "local") },
                context = ToolContext(approval = { true }),
            )
            assertFalse(disconnected.isError)
            assertTrue(closed)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun aggregateToolBudgetIsReleasedWhenServerDisconnects() = runTest {
        fun transport() = object : McpTransport {
            override suspend fun request(method: String, params: JsonObject): JsonObject {
                require(method == "tools/list")
                return buildJsonObject {
                    put("result", buildJsonObject {
                        put("tools", buildJsonArray {
                            repeat(100) { index ->
                                add(buildJsonObject {
                                    put("name", "tool_$index")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                })
                            }
                        })
                    })
                }
            }

            override fun close() = Unit
        }

        val plugin = McpToolBridgePlugin(
            http = OkHttpClient(),
            json = Json,
            transportFactory = { transport() },
        )
        val registry = PluginRegistry()
        registry.install(plugin)

        assertTrue(plugin.connectHttpFromUi(registry.context, "first", "https://example.com/first").contains("已连接"))
        val overBudget = runCatching {
            plugin.connectHttpFromUi(registry.context, "second", "https://example.com/second")
        }
        assertTrue(overBudget.isFailure)
        assertTrue(overBudget.exceptionOrNull()?.message.orEmpty().contains("工具总数"))

        plugin.disconnectFromUi(registry.context, "first")
        assertTrue(plugin.connectHttpFromUi(registry.context, "second", "https://example.com/second").contains("已连接"))
    }

}
