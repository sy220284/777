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
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import okhttp3.OkHttpClient

data class McpServerSnapshot(
    val id: String,
    val transport: String,
    val target: String,
    val tools: List<String>,
)

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
        val client: McpClient,
        val toolNames: List<String>,
    ) {
        data class DisconnectTicket(
            val started: Boolean,
            val drained: CompletableDeferred<Unit>?,
        )

        private var acceptingCalls = true
        private var activeCalls = 0
        private var drained: CompletableDeferred<Unit>? = null
        private var clientClosed = false

        fun beginCall(): Boolean = synchronized(this) {
            if (!acceptingCalls) {
                false
            } else {
                activeCalls += 1
                true
            }
        }

        fun endCall() {
            val completion = synchronized(this) {
                check(activeCalls > 0) { "MCP 活跃调用计数失衡：$id" }
                activeCalls -= 1
                if (activeCalls == 0) drained.also { drained = null } else null
            }
            completion?.complete(Unit)
        }

        fun beginDisconnect(): DisconnectTicket = synchronized(this) {
            if (!acceptingCalls) {
                return@synchronized DisconnectTicket(started = false, drained = drained)
            }
            acceptingCalls = false
            val waitForDrain = if (activeCalls == 0) {
                null
            } else {
                CompletableDeferred<Unit>().also { drained = it }
            }
            DisconnectTicket(started = true, drained = waitForDrain)
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
            if (shouldClose) client.close()
        }
    }

    private val mutex = Mutex()
    private val servers = linkedMapOf<String, ServerBinding>()
    private val connectingIds = linkedSetOf<String>()
    private var connectingDrained: CompletableDeferred<Unit>? = null
    private var acceptingConnections = true

    override suspend fun install(context: HarnessContext) {
        mutex.withLock {
            check(servers.isEmpty() && connectingIds.isEmpty()) {
                "MCP 插件存在未清理的连接状态"
            }
            acceptingConnections = true
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
                    ToolResult(connectHttp(context, input.required("server_id"), input.required("endpoint")))
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
                    ToolResult(
                        connectStdio(
                            context = context,
                            rawId = input.required("server_id"),
                            command = input.requiredStringArray("command"),
                            workingDirectory = input.optional("working_directory"),
                        ),
                    )
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
                    ToolResult(disconnect(context, input.required("server_id")))
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
            connectionDrain to callDrains
        }

        withContext(NonCancellable) {
            shutdown.first?.await()
            shutdown.second.forEach { (_, ticket) -> ticket.drained?.await() }

            val owned = mutex.withLock {
                servers.values.toList().also { bindings ->
                    bindings.forEach { binding ->
                        binding.toolNames.forEach(context.tools::unregister)
                    }
                    servers.clear()
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

    suspend fun serverSnapshots(): List<McpServerSnapshot> = mutex.withLock {
        servers.values.map { binding ->
            McpServerSnapshot(
                id = binding.id,
                transport = binding.transport,
                target = binding.displayTarget,
                tools = binding.toolNames.toList(),
            )
        }
    }

    private suspend fun connectHttp(context: HarnessContext, rawId: String, rawEndpoint: String): String {
        val endpoint = validateEndpoint(rawEndpoint)
        return connect(
            context = context,
            rawId = rawId,
            transport = "http",
            displayTarget = displayEndpoint(endpoint),
        ) { McpClient(transportFactory(endpoint)) }
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
        ) { McpClient(stdioTransportFactory(normalizedCommand, resolvedDirectory)) }
    }

    private suspend fun connect(
        context: HarnessContext,
        rawId: String,
        transport: String,
        displayTarget: String,
        clientFactory: () -> McpClient,
    ): String {
        val serverId = validateServerId(rawId)
        mutex.withLock {
            require(acceptingConnections) { "MCP 插件正在卸载，暂不接受新连接" }
            require(serverId !in servers && serverId !in connectingIds) {
                "MCP 服务已连接或正在连接：$serverId"
            }
            connectingIds += serverId
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
            val definitions = client.listTools()
            require(definitions.size <= MAX_REMOTE_TOOLS) {
                "MCP 服务工具过多：${definitions.size}，上限 $MAX_REMOTE_TOOLS"
            }
            val names = definitions.map { definition -> localToolName(serverId, definition.name) }
            require(names.distinct().size == names.size) {
                "MCP 工具名规范化后发生冲突，请调整服务端工具名"
            }
            val binding = ServerBinding(
                id = serverId,
                transport = transport,
                displayTarget = displayTarget,
                client = client,
                toolNames = names,
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
                                if (!binding.beginCall()) {
                                    ToolResult(
                                        content = "MCP 服务正在断开：$serverId",
                                        isError = true,
                                    )
                                } else {
                                    try {
                                        val result = client.callTool(definition.name, input)
                                        ToolResult(
                                            content = result.toString(),
                                            isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true,
                                        )
                                    } finally {
                                        binding.endCall()
                                    }
                                }
                            },
                        ),
                    )
                    registered += localName
                }
                servers[serverId] = binding
                releaseConnectionReservationLocked(serverId)
            }
            return connectedMessage
        } catch (error: Exception) {
            withContext(NonCancellable) {
                registered.forEach(context.tools::unregister)
                client.close()
                mutex.withLock { releaseConnectionReservationLocked(serverId) }
            }
            throw error
        }
    }

    private suspend fun disconnect(context: HarnessContext, rawId: String): String {
        val serverId = validateServerId(rawId)
        val prepared = mutex.withLock {
            val binding = servers[serverId] ?: return@withLock null
            binding to binding.beginDisconnect()
        } ?: return "MCP 服务未连接：$serverId"

        val (binding, ticket) = prepared
        if (!ticket.started) {
            ticket.drained?.await()
            return "MCP 服务正在断开：$serverId"
        }

        withContext(NonCancellable) {
            ticket.drained?.await()
            mutex.withLock {
                if (servers[serverId] === binding) {
                    binding.toolNames.forEach(context.tools::unregister)
                    servers.remove(serverId)
                }
            }
            binding.closeClientOnce()
        }
        return "已断开 MCP 服务：$serverId；卸载工具 ${binding.toolNames.size} 个"
    }

    private fun releaseConnectionReservationLocked(serverId: String) {
        connectingIds.remove(serverId)
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

    private fun JsonObject.required(key: String): String =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
            ?: error("缺少参数：$key")

    private fun stringSchema(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private companion object {
        const val MAX_REMOTE_TOOLS = 128
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
            "mcp_disconnect",
        )
    }
}
