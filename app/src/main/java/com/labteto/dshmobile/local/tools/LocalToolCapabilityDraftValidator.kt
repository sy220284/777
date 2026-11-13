package com.labteto.dshmobile.local.tools

import com.labteto.dshmobile.harness.tools.JsonSchemaValidator
import com.labteto.dshmobile.harness.tools.ToolExposure
import com.labteto.dshmobile.harness.tools.ToolRegistry
import com.labteto.dshmobile.uicontract.UiToolDraftValidation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Only the active ToolRegistry may validate a user-authored preview. A schema copied from a
 * previous registration is not authority. This method never creates ToolContext, approves,
 * invokes, or retries a tool (including tools that can incur cost).
 */
internal fun validateCapabilityDraftAgainstRegistry(
    registry: ToolRegistry, name: String, expectedSchema: JsonObject, argumentsJson: String,
): UiToolDraftValidation {
    fun reject(why: String) = UiToolDraftValidation.Rejected(why)
    if (argumentsJson.toByteArray(Charsets.UTF_8).size > 16_384)
        return reject("工具参数超过 16 KB，不能放入 Work 草稿")
    val tool = registry.get(name) ?: return reject("工具已卸载或未注册")
    if (tool.exposure == ToolExposure.INTERNAL) return reject("内部工具不允许用户通过此入口使用")
    val function = tool.schema["function"] as JsonObject
    val current = function["parameters"] as JsonObject
    if (expectedSchema != current) return reject("工具定义已更新，请重新选择能力")
    val value = try {
        Json.parseToJsonElement(argumentsJson)
    } catch (_: SerializationException) {
        return reject("工具参数不是合法 JSON")
    }
    val arguments = value as? JsonObject ?: return reject("工具参数必须是 JSON 对象")
    val invalid = JsonSchemaValidator.validate(arguments, current)
    return if (invalid == null) UiToolDraftValidation.Valid else reject(invalid)
}
