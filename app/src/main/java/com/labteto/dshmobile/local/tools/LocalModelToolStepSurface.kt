package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.agent.AgentToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Freezes the exact tool names exposed to one model step and rejects provider/model drift. */
internal class LocalModelToolStepSurface {
    private var allowedNames: Set<String> = emptySet()

    fun capture(schemas: JsonArray): JsonArray {
        allowedNames = schemas.mapNotNull { element ->
            val function = (element as? JsonObject)?.get("function") as? JsonObject
            (function?.get("name") as? JsonPrimitive)?.contentOrNull
        }.toSet()
        return schemas
    }

    fun allows(name: String): Boolean = LocalToolPolicy.isVisibleCall(name, allowedNames)

    fun hiddenCallResult(name: String) = AgentToolResult(
        content = "模型调用了本步骤未暴露的工具：$name",
        isError = true,
        errorCode = "TOOL_NOT_EXPOSED",
        recoveryHint = "先使用 capability_search，等待下一轮工具表更新后再调用。",
    )
}
