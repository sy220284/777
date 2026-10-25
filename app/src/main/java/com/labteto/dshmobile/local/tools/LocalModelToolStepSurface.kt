package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolCall
import com.labteto.dshmobile.harness.agent.AgentToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.labteto.dshmobile.harness.tools.ToolBindingSnapshot
import kotlinx.coroutines.withContext

/** Freezes the exact tool names exposed to one model step and rejects provider/model drift. */
internal class LocalModelToolStepSurface {
    private var allowedNames: Set<String> = emptySet()
    private var bindings: ToolBindingSnapshot? = null

    fun capture(schemas: JsonArray, capturedBindings: ToolBindingSnapshot? = null): JsonArray {
        bindings = capturedBindings
        allowedNames = schemas.mapNotNull { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.contentOrNull
        }.toSet()
        return schemas
    }

    suspend fun <T> executeBound(block: suspend () -> T): T {
        val captured = bindings ?: return block()
        return withContext(captured) { block() }
    }

    fun allows(name: String): Boolean = LocalToolPolicy.isVisibleCall(name, allowedNames)

    /** The batch path must enforce the same frozen model surface as single-tool dispatch. */
    suspend fun executeVisibleBatch(
        calls: List<AgentToolCall>,
        execute: suspend (List<AgentToolCall>) -> List<AgentToolResult>,
    ): List<AgentToolResult> {
        val visible = calls.filter { allows(it.name) }
        val executed = if (visible.isEmpty()) emptyList() else execute(visible)
        check(executed.size == visible.size) { "已暴露工具的批量执行结果数量不匹配" }
        val results = executed.iterator()
        return calls.map { call ->
            if (allows(call.name)) results.next() else hiddenCallResult(call.name)
        }
    }

    fun hiddenCallResult(name: String) = AgentToolResult(
        content = "模型调用了本步骤未暴露的工具：$name",
        isError = true,
        errorCode = "TOOL_NOT_EXPOSED",
        recoveryHint = "新注册的 MCP 工具只能在下一次模型请求获得工具定义后调用；先用 capability_search 按精确工具名检查、加载，不能在当前步骤猜测调用或重复执行写入。",
    )
}
