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
    /** Exact Schema member path, without splitting user-controlled property names. */
    val path: List<String> = listOf(name),
)

internal fun v6ToolFormFields(schema: JsonObject): List<V6ToolFormField> {
    val result = mutableListOf<V6ToolFormField>()
    val keys = mutableSetOf<String>()
    fun visit(owner: JsonObject, path: List<String>, depth: Int) {
        require(depth <= 5) { "工具对象嵌套超过五层" }
        val properties = owner["properties"] as? JsonObject ?: return
        val required = (owner["required"] as? JsonArray).orEmpty().map {
            (it as JsonPrimitive).content
        }.toSet()
        for ((name, element) in properties) {
            val field = element as? JsonObject
                ?: throw IllegalArgumentException("字段定义必须是对象：$name")
            val type = (field["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("字段缺少明确类型：$name")
            require(type in setOf("string", "number", "integer", "boolean", "object", "array")) {
                "工具包含未支持的输入字段类型：$name / $type"
            }
            val currentPath = path + name
            if (type == "object" && field["properties"] is JsonObject) {
                visit(field, currentPath, depth + 1)
            } else {
                val key = currentPath.joinToString(".")
                require(keys.add(key)) { "字段显示路径冲突：$key" }
                val values = (field["enum"] as? JsonArray).orEmpty().map { choice ->
                    (choice as? JsonPrimitive)?.takeIf { it.isString }?.content ?: choice.toString()
                }
                require(values.size <= 32) { "枚举选项太多：$key" }
                result += V6ToolFormField(
                    key, type, name in required,
                    (field["title"] as? JsonPrimitive)?.contentOrNull?.take(120) ?: key,
                    (field["description"] as? JsonPrimitive)?.contentOrNull?.take(240).orEmpty(),
                    values, currentPath,
                )
                require(result.size <= 64) { "工具表单字段超过 64 项" }
            }
        }
    }
    visit(schema, emptyList(), 0)
    return result
}

/** This representation is preview text only, never a ToolContext or execution permission. */
internal fun v6ToolArgumentsJson(schema: JsonObject, values: Map<String, String>): String {
    val fields = v6ToolFormFields(schema)
    require(values.keys.all { key -> fields.any { it.name == key } }) { "不能添加 Schema 之外的字段" }
    val tree = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    fun putPath(target: MutableMap<String, kotlinx.serialization.json.JsonElement>,
        path: List<String>, value: kotlinx.serialization.json.JsonElement) {
        val key = path.first()
        if (path.size == 1) {
            target[key] = value
        } else {
            val child = (target[key] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
            putPath(child, path.drop(1), value)
            target[key] = JsonObject(child)
        }
    }
    for (field in fields) {
        val raw = values[field.name]?.takeIf { it.isNotBlank() } ?: continue
        require(raw.length <= 4_096) { "输入字段太长：${field.name}" }
        val value = when (field.type) {
            "string" -> JsonPrimitive(raw)
            else -> try { Json.parseToJsonElement(raw) }
                catch (error: kotlinx.serialization.SerializationException) {
                    throw IllegalArgumentException("字段需要有效的 ${field.type} JSON：${field.name}", error)
                }
        }
        putPath(tree, field.path, value)
    }
    return JsonObject(tree).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 16_384) { "工具参数超过大小预算" }
    }
}
