package com.labteto.dshmobile.uipack.v6

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A bounded, read-only rendering of an actual registered tool schema. No function in this
 * file can submit a tool call. The original ToolRegistry rechecks registration and validates
 * the assembled draft independently before it may become a user message.
 */
internal data class V6ToolFormField(
    val name: String,
    val type: String,
    val required: Boolean,
    val label: String,
    val description: String,
    val choices: List<String>,
)

internal fun v6ToolFormFields(schema: JsonObject): List<V6ToolFormField> {
    val properties = schema["properties"] as? JsonObject ?: return emptyList()
    require(properties.size <= 32) { "工具输入字段超过 32 项" }
    val required = (schema["required"] as? JsonArray).orEmpty().map {
        (it as JsonPrimitive).content
    }.toSet()
    return properties.map { (name, element) ->
        val field = element as JsonObject
        val type = (field["type"] as JsonPrimitive).content
        require(type in setOf("string", "number", "integer", "boolean", "object", "array")) {
            "工具包含未支持的输入字段类型：$name / $type"
        }
        val values = (field["enum"] as? JsonArray).orEmpty().map { choice ->
            (choice as? JsonPrimitive)?.takeIf { it.isString }?.content ?: choice.toString()
        }
        require(values.size <= 32) { "枚举选项太多：$name" }
        V6ToolFormField(name, type, name in required,
            (field["title"] as? JsonPrimitive)?.contentOrNull?.take(120) ?: name,
            (field["description"] as? JsonPrimitive)?.contentOrNull?.take(240).orEmpty(),
            values)
    }
}

/** This representation is preview text only, never a ToolContext or execution permission. */
internal fun v6ToolArgumentsJson(schema: JsonObject, values: Map<String, String>): String {
    val fields = v6ToolFormFields(schema)
    require(values.keys.all { key -> fields.any { it.name == key } }) { "不能添加 Schema 之外的字段" }
    return buildJsonObject {
        for (field in fields) {
            val raw = values[field.name]?.takeIf { it.isNotBlank() } ?: continue
            require(raw.length <= 4_096) { "输入字段太长：${field.name}" }
            val element = when (field.type) {
                "string" -> JsonPrimitive(raw)
                else -> try { Json.parseToJsonElement(raw) }
                    catch (e: kotlinx.serialization.SerializationException) {
                        throw IllegalArgumentException("字段需要有效的 ${field.type} JSON：${field.name}", e)
                    }
            }
            put(field.name, element)
        }
    }.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 16_384) { "工具参数超过大小预算" }
    }
}
