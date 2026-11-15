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
        onUnexposed: (String) -> Boolean = { false },
        execute: suspend (List<AgentToolCall>) -> List<AgentToolResult>,
    ): List<AgentToolResult> {
        val visible = calls.filter { allows(it.name) }
        val executed = if (visible.isEmpty()) emptyList() else execute(visible)
        check(executed.size == visible.size) { "已暴露工具的批量执行结果数量不匹配" }
        val results = executed.iterator()
        return calls.map { call ->
            if (allows(call.name)) results.next()
            else hiddenCallResult(call.name, onUnexposed(call.name))
        }
    }

    fun hiddenCallResult(name: String, queuedForNextStep: Boolean = false) = AgentToolResult(
        content = "模型调用了本步骤未暴露的工具：$name",
        isError = true,
        errorCode = "TOOL_NOT_EXPOSED",
        recoveryHint = if (queuedForNextStep)
            "工具已加入下一模型步骤优先候选；仍须实际进入模型工具表并通过权限审批，本步骤没有执行此工具或产生写入。"
        else
            "工具未进入本步骤工具表；通过 capability_search 查询当前可用能力，再在下一模型步骤选择调用。不要重复执行未核实的写入。",
    )
}
