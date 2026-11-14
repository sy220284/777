package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.HarnessTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Removes options whose runtime semantics are not read-only in planning/read-only scopes. */
internal fun HarnessTool.forReadOnlyModelScope(): HarnessTool {
    if (name == "code_mode_run") {
        val function = schema["function"] as? JsonObject ?: return this
        val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val scoped = description +
            "；当前规划模式只允许全由只读/网络查询工具组成的步骤列表，可批量读取和检查。" +
            "一旦包含写文件、命令或设备操作，整段脚本在首步前拒绝。真实工具权限和审批仍逐步生效。"
        return copy(schema = JsonObject(schema + ("function" to JsonObject(
            function + ("description" to JsonPrimitive(scoped))
        ))))
    }
    if (name != "web_fetch") return this
    val function = schema["function"] as? JsonObject ?: return this
    val parameters = function["parameters"] as? JsonObject ?: return this
    val properties = parameters["properties"] as? JsonObject ?: return this
    val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val scopedDescription = description
        .replace("大响应会自动完整落盘并返回工作区路径", "大响应仅返回预览")
        .let { text ->
            if ("当前只读作用域不写入工作区且禁止后台任务" in text) text
            else text + "；当前只读作用域不写入工作区且禁止后台任务"
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
