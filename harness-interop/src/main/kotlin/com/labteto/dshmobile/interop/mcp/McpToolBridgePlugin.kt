package com.labteto.dshmobile.interop.mcp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolMetadata
import com.labteto.dshmobile.harness.tools.ToolResult
import com.labteto.dshmobile.harness.tools.functionToolSchema
import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/** Accepted transport results remain lossless until the app's durable output store projects them. */
internal fun completeMcpToolResult(result: JsonObject): String = result.toString()

/** Explicit MCP failures; never expose remote response bodies or credential-bearing endpoint URLs. */
internal fun mcpExtensionFailure(serverId: String, error: Exception, connecting: Boolean): ToolResult {
    val code = when {
        error.message.orEmpty().contains("正在重连") -> "MCP_CONNECTION_BUSY"
        error.message.orEmpty().contains("MCP 服务未连接") -> "MCP_NOT_CONNECTED"
        error is McpHttpException -> when (error.statusCode) {
            401, 403 -> "MCP_AUTH_REQUIRED"
            404, 410 -> "MCP_ENDPOINT_UNAVAILABLE"
            429 -> "MCP_RATE_LIMITED"
            else -> "MCP_HTTP_FAILED"
        }
        error is McpRpcException && error.code == -32601 -> "MCP_METHOD_NOT_SUPPORTED"
        error is IOException -> "MCP_TRANSPORT_FAILED"
        else -> if (connecting) "MCP_CONNECT_FAILED" else "MCP_REMOTE_CALL_FAILED"
    }
    val hint = when (code) {
        "MCP_CONNECTION_BUSY" -> "服务正在重连，请等待当前操作结束后再查看连接状态。"
        "MCP_METHOD_NOT_SUPPORTED" -> "此 MCP 服务未实现请求的方法；请检查资源或工具能力声明，其他已注册方法仍可使用。"
        "MCP_NOT_CONNECTED" -> "服务已断开，请先连接 MCP 服务并重新发现可用工具。"
        "MCP_AUTH_REQUIRED" -> "核实 MCP 服务认证信息和访问权限，并重新建立连接。"
        "MCP_ENDPOINT_UNAVAILABLE" -> "核实 MCP 端点和协议；先断开再使用正确地址连接。"
        "MCP_RATE_LIMITED" -> "MCP 服务正在限流，请稍后检查服务状态。"
        "MCP_TRANSPORT_FAILED" -> "检查网络或本地 MCP 进程是否运行；必要时断开后重新连接。"
        else -> if (connecting) "检查服务进程、工作目录、网络和启动命令。"
            else "请求可能已到达远端；先核对实际执行结果，禁止盲目重放写入请求。"
    }
    return ToolResult(
        content = "MCP 服务 $serverId ${if (connecting) "连接" else "执行"}失败" +
            (if (error is McpHttpException) "（HTTP ${error.statusCode}）" else "") + "：$hint",
        isError = true,
        errorCode = code,
        retryable = false,
        recoveryHint = hint,
    )
}


enum class McpServerLifecycle { CONNECTING, REGISTERED, RECONNECTING, DISCONNECTING, FAILED }
data class McpServerSnapshot(
    val id: String,
    val transport: String,
    val target: String,
    val tools: List<String>,
    val lifecycle: McpServerLifecycle = McpServerLifecycle.REGISTERED,
)

/** A single ordered Owner transition; null means a server was removed, not success. */
data class McpLifecycleEvent(
    val sequence: Long,
    val serverId: String,
    val snapshot: McpServerSnapshot?,
)

/** Replay is bounded. A consumer detecting a gap/epoch change must request a fresh Owner snapshot. */
data class McpLifecycleHistory(
    val incarnation: String,
    val latestSequence: Long,
    val events: List<McpLifecycleEvent>,
    val requiresSnapshot: Boolean,
    /** Captured under the same Owner mutex and sequence as the gap marker. */
    val snapshot: List<McpServerSnapshot>? = null,
)

/** Exact on-demand ping result; never a tool permission or continuous-health claim. */
enum class McpServerHealth { RESPONDED, BUSY, NOT_CONNECTED, UNCONFIRMED }

/**
 * Bridges HTTP and stdio MCP servers into the native Harness Tool Registry.
 *
 * Remote/local-process tool declarations are treated as untrusted metadata: every discovered MCP tool is
 * registered as PRIVILEGED + ALWAYS approval regardless of server annotations.
 */
class McpToolBridgePlugin(
    private val http: OkHttpClient,
    private val json: Json,
    private val workspaceRoot: File? = null,
    private val stdioCommandResolver: (List<String>) -> List<String> = { it },
    private val stdioEnvironmentProvider: () -> Map<String, String> = { emptyMap() },
    private val transportFactory: (String) -> McpTransport = { endpoint ->
        McpNegotiatingHttpTransport(endpoint, http, json)
    },
    private val httpCredentialProvider: suspend (String, String) -> String? = { _, _ -> null },
    private val authorizedTransportFactory: (String, String) -> McpTransport = { endpoint, bearer ->
        val uri = URI(endpoint)
        val scheme = uri.scheme?.lowercase()
        val host = requireNotNull(uri.host)
        require(scheme == "https" || scheme == "http" &&
            host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "远程 MCP Bearer 认证仅允许 HTTPS"
        }
        val expectedPort = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        val builder = http.newBuilder().followRedirects(false).followSslRedirects(false)
        // Run before caller-provided interceptors, which may implement mock transports
        // or additional request routing. Never expose an unauthenticated first hop.
        builder.interceptors().add(0, Interceptor { chain ->
            val target = chain.request().url
            require(target.scheme == scheme && target.host.equals(host, true) &&
                target.port == expectedPort) { "MCP 认证请求偏离已授权服务地址" }
            chain.proceed(chain.request().newBuilder()
                .header("Authorization", "Bearer $bearer").build())
        })
        val authenticated = builder.build()
        McpNegotiatingHttpTransport(endpoint, authenticated, json)
    },
    private val stdioTransportFactory: (List<String>, File?) -> McpTransport = { command, workingDirectory ->
        McpNegotiatingStdioTransport(
            command = command,
            json = json,
            workingDirectory = workingDirectory,
            commandResolver = stdioCommandResolver,
            environmentProvider = stdioEnvironmentProvider,
        )
    },
) : HarnessPlugin {
    override val id: String = "mcp-bridge"

    private class ServerBinding(
        val id: String,
        val transport: String,
        val displayTarget: String,
        val clientFactory: suspend () -> McpClient,
        val client: McpClient,
        val toolNames: List<String>,
        val definitionBytes: Int,
        val remoteToolCount: Int,
    ) {
        data class DisconnectTicket(
            val started: Boolean,
            val drained: CompletableDeferred<Unit>?,
            val calls: List<Job>,
            val serverId: String,
        ) {
            suspend fun cancelAndDrain(except: Job? = null) {
                calls.filterNot { it === except }.forEach {
                    it.cancel(CancellationException("MCP 连接所有者取消在途调用：$serverId"))
                }
                drained?.await()
            }
        }

        private var acceptingCalls = true
        private val activeCalls = mutableSetOf<Job>()
        private var drained: CompletableDeferred<Unit>? = null
        private var clientClosed = false
        var watchJob: Job? = null
        private var failedTransport = false
        fun isTransportFailed(): Boolean = synchronized(this) { failedTransport }
        fun markTransportFailed(): DisconnectTicket = synchronized(this) {
            failedTransport = true
            beginDisconnect()
        }

        fun beginCall(job: Job): Boolean = synchronized(this) {
            if (!acceptingCalls) {
                false
            } else {
                check(activeCalls.add(job)) { "MCP 调用作用域重复：$id" }
                true
            }
        }

        fun endCall(job: Job) {
            val completion = synchronized(this) {
                check(activeCalls.remove(job)) { "MCP 活跃调用归属失衡：$id" }
                if (activeCalls.isEmpty()) drained.also { drained = null } else null
            }
            completion?.complete(Unit)
        }

        fun isAcceptingCalls(): Boolean = synchronized(this) { acceptingCalls }

        fun beginDisconnect(): DisconnectTicket = synchronized(this) {
            if (!acceptingCalls) {
                return@synchronized DisconnectTicket(started = false, drained = drained, calls = activeCalls.toList(), serverId = id)
            }
            acceptingCalls = false
            val waitForDrain = if (activeCalls.isEmpty()) {
                null
            } else {
                CompletableDeferred<Unit>().also { drained = it }
            }
            DisconnectTicket(started = true, drained = waitForDrain, calls = activeCalls.toList(), serverId = id)
        }

        fun closeClientOnce() {
            val shouldClose = synchronized(this) {
                if (clientClosed) {
                    false
                } else {
                    clientClosed = true
                    true
                }
            }
            if (shouldClose) {
                watchJob?.cancel()
                client.close()
            }
        }
    }

    private val mutex = Mutex()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // The MCP owner publishes lifecycle invalidations at each committed state transition.
    // Readers subsequently obtain the actual registry snapshot under the same mutex.
    private val lifecycleRevision = MutableStateFlow(0L)
    val lifecycleRevisions: StateFlow<Long> = lifecycleRevision.asStateFlow()
    private val incarnation = java.util.UUID.randomUUID().toString()
    private var nextLifecycleSequence = 0L
    private var previousLifecycle = emptyMap<String, McpServerSnapshot>()
    private val lifecycleHistory = ArrayDeque<McpLifecycleEvent>()

    /**
     * Called under the MCP Owner mutex after every state mutation. Diff the actual
     * authoritative registry/reservations, not a shadow UI state machine.
     * All subscribers get the same monotonic sequence, including disconnect/crash.
     */
    private fun signalLifecycleChangedLocked() {
        val current = currentLifecycleLocked().associateBy(McpServerSnapshot::id)
        (previousLifecycle.keys + current.keys).toSortedSet().forEach { id ->
            if (previousLifecycle[id] != current[id]) {
                lifecycleHistory.addLast(McpLifecycleEvent(++nextLifecycleSequence, id, current[id]))
                if (lifecycleHistory.size > 512) lifecycleHistory.removeFirst()
            }
        }
        previousLifecycle = current
        lifecycleRevision.value += 1L
    }

    /** Server snapshots are only authenticated MCP Owner facts, never permissions. */
    private fun currentLifecycleLocked(): List<McpServerSnapshot> {
        val registered = servers.values.map { binding ->
            val phase = when {
                binding.id in reconnectingIds -> McpServerLifecycle.RECONNECTING
                binding.isTransportFailed() -> McpServerLifecycle.FAILED
                !binding.isAcceptingCalls() -> McpServerLifecycle.DISCONNECTING
                else -> McpServerLifecycle.REGISTERED
            }
            McpServerSnapshot(binding.id, binding.transport, binding.displayTarget,
                if (binding.isTransportFailed()) emptyList() else binding.toolNames.toList(), phase)
        }
        val pending = (connectingIds + reconnectingIds)
            .filterNot(servers::containsKey)
            .map { id -> McpServerSnapshot(id, "", "", emptyList(),
                if (id in reconnectingIds) McpServerLifecycle.RECONNECTING
                else McpServerLifecycle.CONNECTING) }
        return registered + pending
    }

    /** Return ordered transitions or explicitly require a full re-snapshot on loss. */
    suspend fun lifecycleEventsSince(
        expectedIncarnation: String?, afterSequence: Long, limit: Int = 80,
    ): McpLifecycleHistory = mutex.withLock {
        require(afterSequence >= -1L && limit in 1..200)
        val oldest = lifecycleHistory.firstOrNull()?.sequence ?: nextLifecycleSequence + 1L
        val needsSnapshot = (expectedIncarnation != null && expectedIncarnation != incarnation) ||
            (oldest > 1L && afterSequence < oldest - 1L) ||
            afterSequence > nextLifecycleSequence
        McpLifecycleHistory(incarnation, nextLifecycleSequence,
            if (needsSnapshot) emptyList() else lifecycleHistory.asSequence()
                .filter { it.sequence > afterSequence }.take(limit).toList(),
            requiresSnapshot = needsSnapshot,
            snapshot = if (needsSnapshot) currentLifecycleLocked() else null,
        )
    }

    /** Agent/plugin subscribers consume the same ordered Owner history as V6, without polling. */
    fun observeLifecycle(): kotlinx.coroutines.flow.Flow<McpLifecycleHistory> = flow {
        var cursor = -1L
        var expected: String? = null
        lifecycleRevisions.collect {
            do {
                val page = lifecycleEventsSince(expected, cursor)
                emit(page)
                expected = page.incarnation
                cursor = if (page.requiresSnapshot) page.latestSequence
                    else page.events.lastOrNull()?.sequence ?: page.latestSequence
            } while (!page.requiresSnapshot && cursor < page.latestSequence)
        }
    }

    private val servers = linkedMapOf<String, ServerBinding>()
    private val connectingIds = linkedSetOf<String>()
    private val reconnectingIds = linkedSetOf<String>()
    private var connectingDrained: CompletableDeferred<Unit>? = null
    private var acceptingConnections = true

    override suspend fun install(context: HarnessContext) {
        mutex.withLock {
            check(servers.isEmpty() && connectingIds.isEmpty()) {
                "MCP 插件存在未清理的连接状态"
            }
            acceptingConnections = true
            signalLifecycleChangedLocked()
        }
        context.tools.register(
            HarnessTool(
                name = "mcp_http_connect",
                schema = functionToolSchema(
                    name = "mcp_http_connect",
                    description = "连接 HTTP MCP 服务，发现工具并注册到当前 Harness",
                    properties = buildJsonObject {
                        put("server_id", stringSchema("短标识，只允许字母、数字、下划线和横线"))
                        put("endpoint", stringSchema("HTTP 或 HTTPS MCP 地址"))
                    },
                    required = setOf("server_id", "endpoint"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = CONNECT_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "MCP",
                    discoveryKeywords = MCP_DISCOVERY_KEYWORDS,
                    requirements = listOf("目标 MCP HTTP/HTTPS 服务必须可访问"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val serverId = input.required("server_id")
                    try {
                        ToolResult(connectHttp(context, serverId, input.required("endpoint")))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mcpExtensionFailure(serverId, error, connecting = true)
                    }
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_stdio_connect",
                schema = functionToolSchema(
                    name = "mcp_stdio_connect",
                    description = "启动设备上已存在的 stdio MCP 进程，发现工具并注册到当前 Harness",
                    properties = buildJsonObject {
                        put("server_id", stringSchema("短标识，只允许字母、数字、下划线和横线"))
                        put("command", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("minItems", 1)
                            put("maxItems", MAX_COMMAND_ARGS)
                        })
                        put("working_directory", stringSchema("可选工作目录；必须位于本机 Harness 工作区内"))
                    },
                    required = setOf("server_id", "command"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = CONNECT_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "MCP",
                    discoveryKeywords = MCP_DISCOVERY_KEYWORDS,
                    requirements = listOf("目标 stdio 命令必须已安装且工作目录位于 Harness 工作区内"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val serverId = input.required("server_id")
                    try {
                        ToolResult(connectStdio(context, serverId, input.requiredStringArray("command"),
                            input.optional("working_directory")))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mcpExtensionFailure(serverId, error, connecting = true)
                    }
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_server_list",
                schema = functionToolSchema(
                    name = "mcp_server_list",
                    description = "列出当前已连接的 HTTP/stdio MCP 服务与已注册工具",
                ),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = 5_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "MCP",
                    discoveryKeywords = MCP_DISCOVERY_KEYWORDS,
                    requirements = emptyList(),
                ),
                executor = HarnessToolExecutor { _, _, _ ->
                    ToolResult(listServers())
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_lifecycle_events",
                schema = functionToolSchema(
                    name = "mcp_lifecycle_events",
                    description = "读取有序 MCP 连接生命周期；支持等待真实变更。游标失效时返回当前快照，不授予调用权限。",
                    properties = buildJsonObject {
                        put("incarnation", buildJsonObject { put("type", "string") })
                        put("after_sequence", buildJsonObject { put("type", "integer"); put("minimum", -1) })
                        put("wait_for_change", buildJsonObject { put("type", "boolean") })
                    },
                ),
                access = ToolAccess.READ_ONLY,
                approvalPolicy = ToolApprovalPolicy.NEVER,
                timeoutMillis = CONNECT_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(family = "MCP", discoveryKeywords = MCP_DISCOVERY_KEYWORDS),
                executor = HarnessToolExecutor { _, input, _ ->
                    val expected = input.optional("incarnation")
                    val after = input["after_sequence"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
                    var page = lifecycleEventsSince(expected, after)
                    if (input["wait_for_change"]?.jsonPrimitive?.booleanOrNull == true &&
                        !page.requiresSnapshot && page.events.isEmpty()) {
                        // Subscribe to the existing Owner revision; no heartbeat network request.
                        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS - 5_000L) {
                            lifecycleRevisions.first {
                                val current = lifecycleEventsSince(expected, after)
                                current.requiresSnapshot || current.latestSequence > after
                            }
                        }
                        page = lifecycleEventsSince(expected, after)
                    }
                    ToolResult(buildJsonObject {
                        put("incarnation", page.incarnation)
                        put("latest_sequence", page.latestSequence)
                        put("requires_snapshot", page.requiresSnapshot)
                        put("events", buildJsonArray {
                            page.events.forEach { event -> add(buildJsonObject {
                                put("sequence", event.sequence); put("server_id", event.serverId)
                                put("state", event.snapshot?.lifecycle?.name ?: "REMOVED")
                            }) }
                        })
                        if (page.requiresSnapshot) put("servers", buildJsonArray {
                            page.snapshot.orEmpty().forEach { server -> add(buildJsonObject {
                                put("server_id", server.id); put("transport", server.transport)
                                put("state", server.lifecycle.name)
                                put("tools", buildJsonArray { server.tools.forEach { add(JsonPrimitive(it)) } })
                            }) }
                        })
                    }.toString())
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_reconnect",
                schema = functionToolSchema(
                    name = "mcp_reconnect",
                    description = "显式重建失效 MCP 服务的进程或网络连接；不会重放之前的远端工具请求",
                    properties = buildJsonObject { put("server_id", stringSchema("已连接的 MCP 服务标识")) },
                    required = setOf("server_id"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = CONNECT_TIMEOUT_MILLIS + REMOTE_TOOL_TIMEOUT_MILLIS,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "MCP",
                    discoveryKeywords = MCP_DISCOVERY_KEYWORDS,
                    requirements = listOf("已有连接记录；必须人工批准重新连接"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val serverId = input.required("server_id")
                    try {
                        ToolResult(reconnect(context, serverId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mcpExtensionFailure(serverId, error, connecting = true)
                    }
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_disconnect",
                schema = functionToolSchema(
                    name = "mcp_disconnect",
                    description = "断开一个 MCP 服务并卸载它注册的工具",
                    properties = buildJsonObject {
                        put("server_id", stringSchema("已连接 MCP 服务标识"))
                    },
                    required = setOf("server_id"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = REMOTE_TOOL_TIMEOUT_MILLIS + 5_000L,
                exposure = ToolExposure.OPTIONAL,
                metadata = ToolMetadata(
                    family = "MCP",
                    discoveryKeywords = MCP_DISCOVERY_KEYWORDS,
                    requirements = listOf("目标 MCP 服务必须已连接"),
                ),
                executor = HarnessToolExecutor { _, input, _ ->
                    val serverId = input.required("server_id")
                    try {
                        ToolResult(disconnect(context, serverId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        mcpExtensionFailure(serverId, error, connecting = false)
                    }
                },
            ),
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        val shutdown = mutex.withLock {
            acceptingConnections = false
            val connectionDrain = if (connectingIds.isEmpty()) {
                null
            } else {
                CompletableDeferred<Unit>().also { connectingDrained = it }
            }
            val callDrains = servers.values.toList().map { binding ->
                binding to binding.beginDisconnect()
            }
            signalLifecycleChangedLocked()
            connectionDrain to callDrains
        }

        withContext(NonCancellable) {
            shutdown.first?.await()
            shutdown.second.forEach { (_, ticket) -> ticket.cancelAndDrain() }

            val owned = mutex.withLock {
                servers.values.toList().also { bindings ->
                    bindings.forEach { binding ->
                        binding.toolNames.forEach(context.tools::unregister)
                    }
                    servers.clear()
                    signalLifecycleChangedLocked()
                }
            }
            owned.forEach(ServerBinding::closeClientOnce)
            MANAGEMENT_TOOLS.forEach(context.tools::unregister)
        }
    }

    suspend fun connectHttpFromUi(context: HarnessContext, serverId: String, endpoint: String): String =
        connectHttp(context, serverId, endpoint)

    suspend fun connectStdioFromUi(
        context: HarnessContext,
        serverId: String,
        command: List<String>,
        workingDirectory: String? = null,
    ): String = connectStdio(context, serverId, command, workingDirectory)

    suspend fun disconnectFromUi(context: HarnessContext, serverId: String): String =
        disconnect(context, serverId)

    /**
     * Check a registered server on user request only. The active-call lease prevents
     * uninstall closing its transport mid-ping. A timeout/remote protocol error is
     * unconfirmed, not proof of disconnection or permission denial.
     */
    suspend fun checkServerHealthFromUi(serverId: String): McpServerHealth = coroutineScope {
        val callJob = currentCoroutineContext().job
        val binding = mutex.withLock { servers[serverId] } ?: return@coroutineScope McpServerHealth.NOT_CONNECTED
        if (binding.isTransportFailed()) return@coroutineScope McpServerHealth.NOT_CONNECTED
        if (!binding.beginCall(callJob)) return@coroutineScope McpServerHealth.BUSY
        try {
            val received = withTimeoutOrNull(8_000L) { binding.client.ping(); true } == true
            if (!binding.isAcceptingCalls()) McpServerHealth.BUSY
            else if (received) McpServerHealth.RESPONDED
            else McpServerHealth.UNCONFIRMED
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { McpServerHealth.UNCONFIRMED }
        finally { binding.endCall(callJob) }
    }

    suspend fun serverSnapshots(): List<McpServerSnapshot> = mutex.withLock {
        servers.values.map { binding ->
            McpServerSnapshot(
                id = binding.id,
                transport = binding.transport,
                target = binding.displayTarget,
                tools = if (binding.isTransportFailed()) emptyList() else binding.toolNames.toList(),
            )
        }
    }

    /**
     * Actual in-process MCP lifecycle state, including reservations not yet registered.
     * This is not a health probe, transport authorization, nor evidence of a tool call.
     * The existing serverSnapshots() contract intentionally remains registry-only.
     */
    suspend fun lifecycleSnapshots(): List<McpServerSnapshot> = mutex.withLock {
        currentLifecycleLocked()
    }

    private suspend fun connectHttp(context: HarnessContext, rawId: String, rawEndpoint: String): String {
        val endpoint = validateEndpoint(rawEndpoint)
        val serverId = validateServerId(rawId)
        return connect(
            context = context,
            rawId = serverId,
            transport = "http",
            displayTarget = displayEndpoint(endpoint),
            clientFactory = {
                val bearer = httpCredentialProvider(serverId, endpoint)
                McpClient(if (bearer == null) transportFactory(endpoint)
                    else authorizedTransportFactory(endpoint, bearer))
            },
        )
    }

    private suspend fun connectStdio(
        context: HarnessContext,
        rawId: String,
        command: List<String>,
        workingDirectory: String?,
    ): String {
        val normalizedCommand = validateCommand(command)
        val resolvedDirectory = resolveWorkingDirectory(workingDirectory)
        val executable = normalizedCommand.first().substringAfterLast(File.separatorChar)
        return connect(
            context = context,
            rawId = rawId,
            transport = "stdio",
            displayTarget = "stdio:$executable",
            clientFactory = { McpClient(stdioTransportFactory(normalizedCommand, resolvedDirectory)) },
        )
    }

    private suspend fun connect(
        context: HarnessContext,
        rawId: String,
        transport: String,
        displayTarget: String,
        clientFactory: suspend () -> McpClient,
        fromReconnect: Boolean = false,
    ): String {
        val serverId = validateServerId(rawId)
        mutex.withLock {
            require(acceptingConnections) { "MCP 插件正在卸载，暂不接受新连接" }
            require(serverId !in reconnectingIds || fromReconnect) {
                "MCP 服务正在重连：$serverId"
            }
            require(serverId !in servers && serverId !in connectingIds) {
                "MCP 服务已连接或正在连接：$serverId"
            }
            connectingIds += serverId
            signalLifecycleChangedLocked()
        }

        val client = try {
            clientFactory()
        } catch (error: Exception) {
            withContext(NonCancellable) {
                mutex.withLock { releaseConnectionReservationLocked(serverId) }
            }
            throw error
        }
        val registered = mutableListOf<String>()
        try {
            val remoteDefinitions = client.listTools(allowUnsupported = true)
            val resourceDefinitions = resourceToolDefinitions(serverId, remoteDefinitions)
            val resourceMethods = resourceDefinitions.map { it.name }.zip(
                listOf("resources/list", "resources/templates/list", "resources/read"),
            ).toMap()
            val definitions = remoteDefinitions + resourceDefinitions
            require(remoteDefinitions.size <= MAX_REMOTE_TOOLS) {
                "MCP 服务工具过多：${remoteDefinitions.size}，上限 $MAX_REMOTE_TOOLS"
            }
            val names = definitions.map { definition -> localToolName(serverId, definition.name) }
            val definitionBytes = definitions.sumOf { definition ->
                definition.raw.toString().toByteArray(Charsets.UTF_8).size
            }
            require(names.distinct().size == names.size) {
                "MCP 工具名规范化后发生冲突，请调整服务端工具名"
            }
            val binding = ServerBinding(
                id = serverId,
                transport = transport,
                displayTarget = displayTarget,
                clientFactory = clientFactory,
                client = client,
                toolNames = names,
                definitionBytes = definitionBytes,
                remoteToolCount = remoteDefinitions.size,
            )
            val connectedMessage = buildString {
                append("已连接 MCP 服务：").append(serverId)
                append("\n传输：").append(transport)
                append("\n目标：").append(displayTarget)
                append("\n注册工具数：").append(names.size)
                if (names.isNotEmpty()) {
                    append("\n工具：").append(names.joinToString(", "))
                }
            }

            mutex.withLock {
                require(acceptingConnections) { "MCP 插件正在卸载，连接已取消" }
                require(servers.size < MAX_CONNECTED_SERVERS) {
                    "MCP 已连接服务数超过总上限 $MAX_CONNECTED_SERVERS"
                }
                val totalTools = servers.values.sumOf { it.remoteToolCount } + remoteDefinitions.size
                require(totalTools <= MAX_TOTAL_REMOTE_TOOLS) {
                    "MCP 工具总数超过全局上限 $MAX_TOTAL_REMOTE_TOOLS"
                }
                val totalDefinitionBytes = servers.values.sumOf { it.definitionBytes } + definitionBytes
                require(totalDefinitionBytes <= MAX_TOTAL_DEFINITION_BYTES) {
                    "MCP 工具定义总大小超过全局上限"
                }
                definitions.zip(names).forEach { (definition, localName) ->
                    context.tools.register(
                        HarnessTool(
                            name = localName,
                            schema = functionToolSchema(
                                name = localName,
                                description = buildString {
                                    append("MCP[").append(serverId).append("] ")
                                    append(
                                        (definition.description?.takeIf(String::isNotBlank) ?: definition.name)
                                            .take(MAX_REMOTE_DESCRIPTION_CHARS),
                                    )
                                },
                                parameterSchema = normalizeRemoteParameterSchema(definition.inputSchema),
                            ),
                            access = ToolAccess.PRIVILEGED,
                            approvalPolicy = ToolApprovalPolicy.ALWAYS,
                            exposure = ToolExposure.OPTIONAL,
                            metadata = ToolMetadata(
                                family = "MCP",
                                discoveryKeywords = MCP_DISCOVERY_KEYWORDS + setOf(
                                    serverId.take(MAX_DISCOVERY_KEYWORD_CHARS),
                                    definition.name.take(MAX_DISCOVERY_KEYWORD_CHARS),
                                ),
                                requirements = listOf("MCP 服务 $serverId 必须保持连接"),
                                usageNotes = listOf("远端工具声明视为不可信元数据；每次调用都按高权限工具审批"),
                            ),
                            timeoutMillis = REMOTE_TOOL_TIMEOUT_MILLIS,
                            executor = HarnessToolExecutor { _, input, _ ->
                                coroutineScope {
                                    val callJob = currentCoroutineContext().job
                                    if (!binding.beginCall(callJob)) {
                                        ToolResult(
                                            content = "MCP 服务正在断开：$serverId",
                                            isError = true,
                                        )
                                    } else {
                                        var failedCleanup: ServerBinding.DisconnectTicket? = null
                                        try {
                                            val result = when (resourceMethods[definition.name]) {
                                                "resources/list" -> client.listResources((input["cursor"] as? JsonPrimitive)?.contentOrNull)
                                                "resources/templates/list" -> client.listResourceTemplates((input["cursor"] as? JsonPrimitive)?.contentOrNull)
                                                "resources/read" -> client.readResource(input.required("uri"))
                                                else -> client.callTool(definition.name, input)
                                            }
                                            val remoteError = result["isError"]?.jsonPrimitive?.booleanOrNull == true
                                            ToolResult(
                                                content = completeMcpToolResult(result),
                                                richContent = com.labteto.dshmobile.harness.tools.ToolRichContent(result),
                                                isError = remoteError,
                                                errorCode = if (remoteError) "MCP_REMOTE_REJECTED" else null,
                                                recoveryHint = if (remoteError)
                                                    "远端工具报告失败；先检查副作用和参数，不要直接重放。"
                                                    else null,
                                            )
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            // A transport termination invalidates all tool registrations on the
                                            // same server. Semantic tool errors/rate limits do not imply disconnection.
                                            if (error is IOException ||
                                                (error is McpHttpException &&
                                                    error.statusCode in setOf(401, 403, 404, 410))) {
                                                failedCleanup = withContext(NonCancellable) {
                                                    mutex.withLock {
                                                        if (servers[serverId] === binding) {
                                                            binding.markTransportFailed().also { ticket ->
                                                                if (ticket.started) {
                                                                    binding.toolNames.forEach(context.tools::unregister)
                                                                    signalLifecycleChangedLocked()
                                                                }
                                                            }
                                                        } else null
                                                    }
                                                }
                                            }
                                            mcpExtensionFailure(serverId, error, connecting = false)
                                        } finally {
                                            binding.endCall(callJob)
                                            failedCleanup?.let { ticket ->
                                                withContext(NonCancellable) {
                                                    ticket.cancelAndDrain(except = callJob)
                                                    binding.closeClientOnce()
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                        ),
                    )
                    registered += localName
                }
                servers[serverId] = binding
                releaseConnectionReservationLocked(serverId)
                binding.watchJob = client.terminalEvents?.let { failures ->
                    lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        failures.collect {
                            // The transport may have died without any active tool invocation.
                            // Keep FAILED visible for explicit user reconnect. Never replay calls.
                            withContext(NonCancellable) {
                                val ticket = mutex.withLock {
                                    if (servers[serverId] !== binding || binding.isTransportFailed())
                                        return@withLock null
                                    binding.markTransportFailed().also { result ->
                                        if (result.started) {
                                            binding.toolNames.forEach(context.tools::unregister)
                                            signalLifecycleChangedLocked()
                                        }
                                    }
                                }
                                ticket?.cancelAndDrain()
                                if (ticket != null) binding.closeClientOnce()
                            }
                        }
                    }
                }
            }
            return connectedMessage
        } catch (error: Exception) {
            withContext(NonCancellable) {
                // Even if remote transport close fails, release the reservation so one bad
                // server cannot poison subsequent connects or unrelated MCP servers.
                registered.asReversed().forEach { name ->
                    runCatching { context.tools.unregister(name) }
                        .exceptionOrNull()?.let(error::addSuppressed)
                }
                runCatching { client.close() }.exceptionOrNull()?.let(error::addSuppressed)
                mutex.withLock { releaseConnectionReservationLocked(serverId) }
            }
            throw error
        }
    }

    private suspend fun reconnect(context: HarnessContext, rawId: String): String {
        val serverId = validateServerId(rawId)
        val existing = mutex.withLock {
            require(reconnectingIds.add(serverId)) { "MCP 服务正在重连：$serverId" }
            signalLifecycleChangedLocked()
            servers[serverId]
        }
        try {
            requireNotNull(existing) {
                "MCP 服务未连接：$serverId。请先用 mcp_http_connect 或 mcp_stdio_connect 建立连接"
            }
            // Cancel and drain the old binding before reconnecting; never replay tools/call.
            disconnect(context, serverId, fromReconnect = true)
            return connect(context, serverId, existing.transport, existing.displayTarget,
                existing.clientFactory, fromReconnect = true)
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (reconnectingIds.remove(serverId)) signalLifecycleChangedLocked() } }
        }
    }

    private suspend fun disconnect(
        context: HarnessContext,
        rawId: String,
        fromReconnect: Boolean = false,
    ): String {
        val serverId = validateServerId(rawId)
        val prepared = mutex.withLock {
            require(serverId !in reconnectingIds || fromReconnect) {
                "MCP 服务正在重连：$serverId"
            }
            val binding = servers[serverId] ?: return@withLock null
            (binding to binding.beginDisconnect()).also { (_, ticket) ->
                if (ticket.started) signalLifecycleChangedLocked()
            }
        } ?: return "MCP 服务未连接：$serverId"

        val (binding, ticket) = prepared
        if (!ticket.started && !binding.isTransportFailed()) {
            ticket.cancelAndDrain()
            return "MCP 服务正在断开：$serverId"
        }

        withContext(NonCancellable) {
            ticket.cancelAndDrain()
            mutex.withLock {
                if (servers[serverId] === binding) {
                    binding.toolNames.forEach(context.tools::unregister)
                    servers.remove(serverId)
                    signalLifecycleChangedLocked()
                }
            }
            binding.closeClientOnce()
        }
        return "已断开 MCP 服务：$serverId；卸载工具 ${binding.toolNames.size} 个"
    }

    private fun releaseConnectionReservationLocked(serverId: String) {
        if (connectingIds.remove(serverId)) signalLifecycleChangedLocked()
        if (connectingIds.isEmpty()) {
            connectingDrained?.complete(Unit)
            connectingDrained = null
        }
    }

    private suspend fun listServers(): String = mutex.withLock {
        if (servers.isEmpty()) return@withLock "暂无已连接 MCP 服务"
        buildJsonArray {
            servers.values.forEach { binding ->
                add(
                    buildJsonObject {
                        put("server_id", binding.id)
                        put("transport", binding.transport)
                        put("target", binding.displayTarget)
                        put("tools", buildJsonArray {
                            binding.toolNames.forEach { add(JsonPrimitive(it)) }
                        })
                    },
                )
            }
        }.toString()
    }

    private fun validateCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "MCP stdio 命令不能为空" }
        require(command.size <= MAX_COMMAND_ARGS) { "MCP stdio 参数过多，上限 $MAX_COMMAND_ARGS" }
        val normalized = command.map { argument ->
            require(argument.length <= MAX_COMMAND_ARG_LENGTH) {
                "MCP stdio 单个参数过长，上限 $MAX_COMMAND_ARG_LENGTH 字符"
            }
            argument
        }
        require(normalized.first().isNotBlank()) { "MCP stdio 可执行命令不能为空" }
        return normalized
    }

    private fun resolveWorkingDirectory(raw: String?): File? {
        val root = workspaceRoot?.canonicalFile
        if (raw.isNullOrBlank()) return root
        val input = File(raw)
        val resolved = (if (input.isAbsolute) input else File(root ?: File("."), raw)).canonicalFile
        require(resolved.isDirectory) { "MCP stdio 工作目录不存在：${resolved.path}" }
        if (root != null) {
            require(resolved == root || resolved.path.startsWith(root.path + File.separator)) {
                "MCP stdio 工作目录必须位于本机 Harness 工作区内"
            }
        }
        return resolved
    }

    private fun JsonObject.requiredStringArray(key: String): List<String> =
        (this[key] as? JsonArray)
            ?.map { element -> element.jsonPrimitive.content }
            ?.takeIf { it.isNotEmpty() }
            ?: error("缺少参数：$key")

    private fun JsonObject.optional(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)

    private fun validateServerId(raw: String): String {
        val id = raw.trim()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,24}"))) {
            "MCP server_id 仅允许 1..24 位字母、数字、下划线和横线"
        }
        return id
    }

    private fun validateEndpoint(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrElse { error("MCP 地址格式无效") }
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "MCP 地址仅支持 HTTP/HTTPS" }
        require(!uri.host.isNullOrBlank()) { "MCP 地址缺少主机名" }
        require(uri.userInfo == null) { "MCP 地址禁止内嵌用户名或密码" }
        require(uri.fragment == null) { "MCP 地址禁止 fragment" }
        return uri.toString()
    }

    private fun displayEndpoint(endpoint: String): String {
        val uri = URI(endpoint)
        return URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path?.ifBlank { "/" } ?: "/",
            null,
            null,
        ).toString()
    }

    private fun normalizeRemoteParameterSchema(schema: JsonObject): JsonObject {
        if (schema.isEmpty()) {
            return buildJsonObject {
                put("type", "object")
                put("additionalProperties", true)
            }
        }
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        require(type == null || type == "object") {
            "MCP 工具参数根 schema 必须是 object"
        }
        return if (type == null) {
            JsonObject(schema + ("type" to JsonPrimitive("object")))
        } else {
            schema
        }
    }

    private fun localToolName(serverId: String, remoteName: String): String {
        val normalized = remoteName.map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("").trim('_')
        val prefix = "mcp_${serverId}_"
        val available = MAX_TOOL_NAME_LENGTH - prefix.length
        require(available > 0) { "MCP server_id 过长" }
        return prefix + normalized.ifBlank { "tool" }.take(available)
    }

    private fun resourceToolDefinitions(serverId: String, remote: List<McpToolDefinition>): List<McpToolDefinition> = listOf(
        "resources_list" to "分页列出资源（原样返回 nextCursor）",
        "resources_templates_list" to "分页列出资源模板（原样返回 nextCursor）",
        "resources_read" to "读取资源 URI 的完整内容",
    ).map { (baseName, description) ->
        // Synthetic tools must never intercept a same-named legitimate tools/call operation.
        val reserved = remote.map { localToolName(serverId, it.name) }.toSet()
        var name = baseName
        var suffix = 1
        while (localToolName(serverId, name) in reserved) name = "${baseName}_${suffix++}"
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put(if (baseName == "resources_read") "uri" else "cursor", stringSchema(description))
            })
            if (baseName == "resources_read") put("required", buildJsonArray { add(JsonPrimitive("uri")) })
        }
        McpToolDefinition(name, description, schema, buildJsonObject {
            put("name", name); put("description", description); put("inputSchema", schema)
        })
    }

    private fun JsonObject.required(key: String): String =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
            ?: error("缺少参数：$key")

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private companion object {
        const val MAX_REMOTE_TOOLS = 128
        const val MAX_CONNECTED_SERVERS = 8
        const val MAX_TOTAL_REMOTE_TOOLS = 192
        const val MAX_TOTAL_DEFINITION_BYTES = 8 * 1024 * 1024
        const val MAX_TOOL_NAME_LENGTH = 64
        const val MAX_REMOTE_DESCRIPTION_CHARS = 1_024
        const val MAX_DISCOVERY_KEYWORD_CHARS = 128
        const val CONNECT_TIMEOUT_MILLIS = 65_000L
        const val REMOTE_TOOL_TIMEOUT_MILLIS = 65_000L
        const val MAX_COMMAND_ARGS = 32
        const val MAX_COMMAND_ARG_LENGTH = 4_096
        val MCP_DISCOVERY_KEYWORDS = setOf(
            "mcp", "外部工具", "服务", "连接", "扩展", "工具桥接", "http", "stdio",
        )
        val MANAGEMENT_TOOLS = listOf(
            "mcp_http_connect",
            "mcp_stdio_connect",
            "mcp_server_list",
            "mcp_lifecycle_events",
            "mcp_reconnect",
            "mcp_disconnect",
        )
    }
}
