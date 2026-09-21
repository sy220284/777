package com.labteto.dshmobile.interop.mcp

import com.labteto.dshmobile.harness.plugin.HarnessContext
import com.labteto.dshmobile.harness.plugin.HarnessPlugin
import com.labteto.dshmobile.harness.tools.HarnessTool
import com.labteto.dshmobile.harness.tools.HarnessToolExecutor
import com.labteto.dshmobile.harness.tools.ToolAccess
import com.labteto.dshmobile.harness.tools.ToolApprovalPolicy
import com.labteto.dshmobile.harness.tools.ToolResult
import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Bridges HTTP MCP servers into the native Harness Tool Registry.
 *
 * Remote tool declarations are treated as untrusted metadata: every discovered MCP tool is
 * registered as PRIVILEGED + ALWAYS approval regardless of server annotations.
 */
class McpToolBridgePlugin(
    private val http: OkHttpClient,
    private val json: Json,
    private val transportFactory: (String) -> McpTransport = { endpoint ->
        McpNegotiatingHttpTransport(endpoint, http, json)
    },
) : HarnessPlugin {
    override val id: String = "mcp-http-bridge"

    private data class ServerBinding(
        val id: String,
        val endpoint: String,
        val displayEndpoint: String,
        val client: McpClient,
        val toolNames: List<String>,
    )

    private val mutex = Mutex()
    private val servers = linkedMapOf<String, ServerBinding>()

    override suspend fun install(context: HarnessContext) {
        context.tools.register(
            HarnessTool(
                name = "mcp_http_connect",
                schema = functionSchema(
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
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(connect(context, input.required("server_id"), input.required("endpoint")))
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_server_list",
                schema = functionSchema(
                    name = "mcp_server_list",
                    description = "列出当前已连接的 HTTP MCP 服务与已注册工具",
                ),
                access = ToolAccess.READ_ONLY,
                timeoutMillis = 5_000L,
                executor = HarnessToolExecutor { _, _, _ ->
                    ToolResult(listServers())
                },
            ),
        )
        context.tools.register(
            HarnessTool(
                name = "mcp_disconnect",
                schema = functionSchema(
                    name = "mcp_disconnect",
                    description = "断开一个 HTTP MCP 服务并卸载它注册的工具",
                    properties = buildJsonObject {
                        put("server_id", stringSchema("已连接 MCP 服务标识"))
                    },
                    required = setOf("server_id"),
                ),
                access = ToolAccess.PRIVILEGED,
                approvalPolicy = ToolApprovalPolicy.ALWAYS,
                timeoutMillis = 10_000L,
                executor = HarnessToolExecutor { _, input, _ ->
                    ToolResult(disconnect(context, input.required("server_id")))
                },
            ),
        )
    }

    override suspend fun uninstall(context: HarnessContext) {
        mutex.withLock {
            servers.values.toList().forEach { binding ->
                binding.toolNames.forEach(context.tools::unregister)
                binding.client.close()
            }
            servers.clear()
        }
        MANAGEMENT_TOOLS.forEach(context.tools::unregister)
    }

    private suspend fun connect(context: HarnessContext, rawId: String, rawEndpoint: String): String =
        mutex.withLock {
            val serverId = validateServerId(rawId)
            require(serverId !in servers) { "MCP 服务已连接：$serverId" }
            val endpoint = validateEndpoint(rawEndpoint)
            val client = McpClient(transportFactory(endpoint))
            val registered = mutableListOf<String>()
            try {
                val definitions = client.listTools()
                require(definitions.size <= MAX_REMOTE_TOOLS) {
                    "MCP 服务工具过多：${definitions.size}，上限 $MAX_REMOTE_TOOLS"
                }
                val names = definitions.map { definition ->
                    localToolName(serverId, definition.name)
                }
                require(names.distinct().size == names.size) {
                    "MCP 工具名规范化后发生冲突，请调整服务端工具名"
                }

                definitions.zip(names).forEach { (definition, localName) ->
                    context.tools.register(
                        HarnessTool(
                            name = localName,
                            schema = functionSchema(
                                name = localName,
                                description = buildString {
                                    append("MCP[").append(serverId).append("] ")
                                    append(definition.description?.takeIf(String::isNotBlank) ?: definition.name)
                                },
                                properties = definition.inputSchema,
                                rawParameters = true,
                            ),
                            access = ToolAccess.PRIVILEGED,
                            approvalPolicy = ToolApprovalPolicy.ALWAYS,
                            timeoutMillis = REMOTE_TOOL_TIMEOUT_MILLIS,
                            executor = HarnessToolExecutor { _, input, _ ->
                                val result = client.callTool(definition.name, input)
                                ToolResult(
                                    content = result.toString(),
                                    isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true,
                                )
                            },
                        ),
                    )
                    registered += localName
                }

                val binding = ServerBinding(
                    id = serverId,
                    endpoint = endpoint,
                    displayEndpoint = displayEndpoint(endpoint),
                    client = client,
                    toolNames = registered.toList(),
                )
                servers[serverId] = binding
                buildString {
                    append("已连接 MCP 服务：").append(serverId)
                    append("\n地址：").append(binding.displayEndpoint)
                    append("\n注册工具数：").append(registered.size)
                    if (registered.isNotEmpty()) {
                        append("\n工具：").append(registered.joinToString(", "))
                    }
                }
            } catch (error: Exception) {
                registered.forEach(context.tools::unregister)
                client.close()
                throw error
            }
        }

    private suspend fun disconnect(context: HarnessContext, rawId: String): String = mutex.withLock {
        val serverId = validateServerId(rawId)
        val binding = servers.remove(serverId) ?: return@withLock "MCP 服务未连接：$serverId"
        binding.toolNames.forEach(context.tools::unregister)
        binding.client.close()
        "已断开 MCP 服务：$serverId；卸载工具 ${binding.toolNames.size} 个"
    }

    private suspend fun listServers(): String = mutex.withLock {
        if (servers.isEmpty()) return@withLock "暂无已连接 MCP 服务"
        buildJsonArray {
            servers.values.forEach { binding ->
                add(
                    buildJsonObject {
                        put("server_id", binding.id)
                        put("endpoint", binding.displayEndpoint)
                        put("tools", buildJsonArray {
                            binding.toolNames.forEach { add(JsonPrimitive(it)) }
                        })
                    },
                )
            }
        }.toString()
    }

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

    private fun functionSchema(
        name: String,
        description: String,
        properties: JsonObject = JsonObject(emptyMap()),
        required: Set<String> = emptySet(),
        rawParameters: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put(
                "parameters",
                if (rawParameters) {
                    if (properties.isEmpty()) {
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", true)
                        }
                    } else {
                        properties
                    }
                } else {
                    buildJsonObject {
                        put("type", "object")
                        put("properties", properties)
                        put("required", buildJsonArray {
                            required.forEach { add(JsonPrimitive(it)) }
                        })
                        put("additionalProperties", false)
                    }
                },
            )
        })
    }

    private companion object {
        const val MAX_REMOTE_TOOLS = 128
        const val MAX_TOOL_NAME_LENGTH = 64
        const val CONNECT_TIMEOUT_MILLIS = 65_000L
        const val REMOTE_TOOL_TIMEOUT_MILLIS = 65_000L
        val MANAGEMENT_TOOLS = listOf(
            "mcp_http_connect",
            "mcp_server_list",
            "mcp_disconnect",
        )
    }
}
