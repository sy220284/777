package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class ToolAccess {
    READ_ONLY,
    WORKSPACE_WRITE,
    SESSION_WRITE,
    PROCESS,
    AGENT_CONTROL,
    NETWORK,
    DEVICE,
    PRIVILEGED,
}

enum class ToolApprovalPolicy {
    NEVER,
    MUTATION,
    ALWAYS,
}

data class ToolContext(
    val sessionId: String? = null,
    val allowMutation: Boolean = true,
    val attributes: Map<String, Any?> = emptyMap(),
    val approval: (suspend (HarnessTool) -> Boolean)? = null,
)

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
)

fun interface HarnessToolExecutor {
    suspend fun execute(context: ToolContext, input: JsonObject, rawArguments: String): ToolResult
}

data class HarnessTool(
    val name: String,
    val schema: JsonObject,
    val access: ToolAccess = ToolAccess.READ_ONLY,
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.NEVER,
    val timeoutMillis: Long? = null,
    val executor: HarnessToolExecutor,
)

class ToolRegistry {
    private val tools = linkedMapOf<String, HarnessTool>()

    @Synchronized
    fun register(tool: HarnessTool, replace: Boolean = false) {
        require(tool.name.isNotBlank()) { "工具名不能为空" }
        if (!replace) require(tool.name !in tools) { "工具已注册：${tool.name}" }
        tools[tool.name] = tool
    }

    @Synchronized
    fun unregister(name: String): HarnessTool? = tools.remove(name)

    @Synchronized
    fun get(name: String): HarnessTool? = tools[name]

    @Synchronized
    fun names(): List<String> = tools.keys.toList()

    @Synchronized
    fun schemas(): JsonArray = JsonArray(tools.values.map(HarnessTool::schema))

    suspend fun execute(
        name: String,
        input: JsonObject,
        rawArguments: String = input.toString(),
        context: ToolContext = ToolContext(),
    ): ToolResult {
        val tool = synchronized(this) { tools[name] } ?: return ToolResult(
            content = "未知工具：$name",
            isError = true,
        )
        if (!context.allowMutation && tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)) {
            return ToolResult("当前作用域禁止执行会改变状态的工具：$name", isError = true)
        }
        val needsApproval = when (tool.approvalPolicy) {
            ToolApprovalPolicy.NEVER -> false
            ToolApprovalPolicy.ALWAYS -> true
            ToolApprovalPolicy.MUTATION -> tool.access !in setOf(ToolAccess.READ_ONLY, ToolAccess.NETWORK)
        }
        if (needsApproval) {
            val approval = context.approval
                ?: return ToolResult("工具需要人工审批：$name", isError = true)
            if (!approval(tool)) return ToolResult("用户拒绝执行工具：$name", isError = true)
        }
        return tool.executor.execute(context, input, rawArguments)
    }
}
