package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.tools.HarnessTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Removes options whose runtime semantics are not read-only in planning/read-only scopes. */
internal fun HarnessTool.forReadOnlyModelScope(): HarnessTool {
    if (name != "web_fetch") return this
    val function = schema["function"] as? JsonObject ?: return this
    val parameters = function["parameters"] as? JsonObject ?: return this
    val properties = parameters["properties"] as? JsonObject ?: return this
    val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val scopedDescription = description
        .replace("大响应会自动完整落盘并返回工作区路径", "大响应仅返回预览，不写入工作区")
        .let { text ->
            if ("只读作用域禁止后台任务" in text) text
            else text + "；当前只读作用域禁止后台任务"
        }
    return copy(
        schema = JsonObject(schema + ("function" to JsonObject(function + mapOf(
            "description" to JsonPrimitive(scopedDescription),
            "parameters" to JsonObject(
                parameters + ("properties" to JsonObject(properties - "run_in_background")),
            ),
        )))),
    )
}
