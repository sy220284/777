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
    fun visit(owner: JsonObject, path: List<String>, depth: Int, ancestorsRequired: Boolean) {
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
                visit(field, currentPath, depth + 1, ancestorsRequired && name in required)
            } else {
                val key = currentPath.joinToString(".")
                require(keys.add(key)) { "字段显示路径冲突：$key" }
                val values = (field["enum"] as? JsonArray).orEmpty().map { choice ->
                    (choice as? JsonPrimitive)?.takeIf { it.isString }?.content ?: choice.toString()
                }
                require(values.size <= 32) { "枚举选项太多：$key" }
                result += V6ToolFormField(
                    key, type, ancestorsRequired && name in required,
                    (field["title"] as? JsonPrimitive)?.contentOrNull?.take(120) ?: key,
                    (field["description"] as? JsonPrimitive)?.contentOrNull?.take(240).orEmpty(),
                    values, currentPath,
                )
                require(result.size <= 64) { "工具表单字段超过 64 项" }
            }
        }
    }
    visit(schema, emptyList(), 0, true)
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
        val raw = values[field.name]?.takeIf { it.isNotBlank() }
        if (raw == null) {
            require(!field.required) { "缺少必填字段：${field.label}" }
            continue
        }
        require(raw.length <= 4_096) { "输入字段太长：${field.name}" }
        val value = when (field.type) {
            "string" -> JsonPrimitive(raw)
            else -> try { Json.parseToJsonElement(raw) }
                catch (error: kotlinx.serialization.SerializationException) {
                    throw IllegalArgumentException("字段需要有效的 ${field.type} JSON：${field.name}", error)
                }
        }
        val validType = when (field.type) {
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString &&
                value.content in setOf("true", "false")
            "integer" -> value is JsonPrimitive && !value.isString &&
                value.content.matches(Regex("-?(0|[1-9][0-9]*)"))
            "number" -> value is JsonPrimitive && !value.isString &&
                value.content.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            "array" -> value is JsonArray
            "object" -> value is JsonObject
            else -> false
        }
        require(validType) { "字段类型不匹配：${field.label}（需要 ${field.type}）" }
        var spec: JsonObject = schema
        for (member in field.path) {
            val properties = spec["properties"] as? JsonObject
                ?: throw IllegalArgumentException("工具 Schema 字段结构已变化")
            spec = properties[member] as? JsonObject
                ?: throw IllegalArgumentException("工具 Schema 字段已失效")
        }
        val allowed = spec["enum"] as? JsonArray
        require(allowed == null || value in allowed) { "字段不在许可选项中：${field.label}" }
        putPath(tree, field.path, value)
    }
    val arguments = JsonObject(tree)
    // The form may accept a raw object/array field. Validate that whole JSON
    // tree against the *registered* schema, not just its top-level "object" token.
    // Optional parents remain absent until supplied; when present, their required
    // descendants and array item contracts are enforced before reaching the Owner.
    fun validate(spec: JsonObject, value: kotlinx.serialization.json.JsonElement,
        path: String, depth: Int) {
        require(depth <= 10) { "工具输入嵌套深度超出 Schema 预算：$path" }
        val type = (spec["type"] as? JsonPrimitive)?.content
        val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val matches = when (type) {
            null -> true
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString &&
                value.content in setOf("true", "false")
            "integer" -> value is JsonPrimitive && !value.isString &&
                value.content.matches(Regex("-?(0|[1-9][0-9]*)"))
            "number" -> value is JsonPrimitive && !value.isString && value.content.matches(number)
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            else -> false
        }
        require(matches) { "字段类型不匹配：$path（需要 $type）" }
        val choices = spec["enum"] as? JsonArray
        require(choices == null || value in choices) { "字段不在许可选项中：$path" }
        if (value is JsonObject) {
            val required = (spec["required"] as? JsonArray).orEmpty()
                .map { (it as JsonPrimitive).content }
            for (key in required) {
                require(key in value) { "缺少必填字段：$path.$key" }
            }
            val props = spec["properties"] as? JsonObject
            for ((key, child) in value) {
                val nested = props?.get(key) as? JsonObject
                if (nested != null) {
                    validate(nested, child, "$path.$key", depth + 1)
                } else {
                    val extras = spec["additionalProperties"]
                    require(extras != JsonPrimitive(false)) {
                        "未注册的对象字段：$path.$key"
                    }
                    if (extras is JsonObject) {
                        validate(extras, child, "$path.$key", depth + 1)
                    }
                }
            }
        } else if (value is JsonArray) {
            val items = spec["items"] as? JsonObject
            if (items != null) {
                value.forEachIndexed { i, child -> validate(items, child, "$path[$i]", depth + 1) }
            }
            val min = (spec["minItems"] as? JsonPrimitive)?.content?.toIntOrNull()
            val max = (spec["maxItems"] as? JsonPrimitive)?.content?.toIntOrNull()
            require((min == null || value.size >= min) &&
                (max == null || value.size <= max)) { "数组条目数量不符合 Schema：$path" }
        }
    }
    validate(schema, arguments, "parameters", 0)
    return arguments.toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 16_384) { "工具参数超过大小预算" }
    }
}
