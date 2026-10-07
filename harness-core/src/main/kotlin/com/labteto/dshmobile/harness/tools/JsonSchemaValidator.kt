package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared bounded JSON-Schema subset used by tool arguments and structured Agent results.
 */
object JsonSchemaValidator {
    private val supportedTypes = setOf(
        "object",
        "array",
        "string",
        "integer",
        "number",
        "boolean",
    )

    fun validate(
        value: JsonElement,
        schema: JsonObject,
        path: String = "$",
    ): String? = validateValue(value, schema, path)

    fun validateSchema(
        schema: JsonObject,
        requireObjectRoot: Boolean = false,
        maxDepth: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): String? {
        require(maxDepth > 0) { "maxDepth 必须大于 0" }
        require(maxNodes > 0) { "maxNodes 必须大于 0" }
        var visited = 0

        fun visit(current: JsonObject, path: String, depth: Int): String? {
            if (depth > maxDepth) return "$path schema 嵌套超过 $maxDepth 层"
            visited += 1
            if (visited > maxNodes) return "schema 节点超过 $maxNodes 个"

            current.keys.firstOrNull { it !in supportedKeywords }?.let { keyword ->
                return "$path 包含未支持的 JSON Schema 关键字：$keyword"
            }

            val typeElement = current["type"]
            val type = typeElement?.let { element ->
                val primitive = element as? JsonPrimitive
                    ?: return "$path.type 必须是字符串"
                if (!primitive.isString) return "$path.type 必须是字符串"
                primitive.content
            }
            if (type != null && type !in supportedTypes) {
                return "$path.type 不受支持：$type"
            }
            if (depth == 0 && requireObjectRoot && type != "object") {
                return "$path 根类型必须是 object"
            }

            val properties = current["properties"]
            if (properties != null && properties !is JsonObject) {
                return "$path.properties 必须是对象"
            }
            val required = current["required"]
            if (required != null) {
                val array = required as? JsonArray ?: return "$path.required 必须是数组"
                val requiredNames = mutableSetOf<String>()
                array.forEachIndexed { index, value ->
                    val primitive = value as? JsonPrimitive
                        ?: return "$path.required[$index] 必须是字符串"
                    if (!primitive.isString || primitive.content.isBlank()) {
                        return "$path.required[$index] 必须是非空字符串"
                    }
                    if (!requiredNames.add(primitive.content)) {
                        return "$path.required 包含重复字段 ${primitive.content}"
                    }
                }
                val declared = (properties as? JsonObject)?.keys.orEmpty()
                requiredNames.firstOrNull { it !in declared }?.let {
                    return "$path.required 引用了未声明字段：$it"
                }
            }

            val additional = current["additionalProperties"]
            if (
                additional != null &&
                additional !is JsonObject &&
                (additional as? JsonPrimitive)?.booleanOrNull == null
            ) {
                return "$path.additionalProperties 必须是布尔值或对象 schema"
            }

            (properties as? JsonObject)?.forEach { (name, child) ->
                val childSchema = child as? JsonObject
                    ?: return "$path.properties.$name 必须是对象 schema"
                visit(childSchema, "$path.properties.$name", depth + 1)?.let { return it }
            }
            (additional as? JsonObject)?.let { schemaValue ->
                visit(schemaValue, "$path.additionalProperties", depth + 1)?.let { return it }
            }

            val items = current["items"]
            if (items != null) {
                val itemSchema = items as? JsonObject
                    ?: return "$path.items 必须是对象 schema"
                visit(itemSchema, "$path.items", depth + 1)?.let { return it }
            }

            fun nonNegativeInteger(keyword: String): String? {
                val raw = current[keyword] ?: return null
                val value = (raw as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.longOrNull
                    ?: return "$path.$keyword 必须是非负整数"
                if (value < 0L) return "$path.$keyword 必须是非负整数"
                return null
            }
            nonNegativeInteger("minItems")?.let { return it }
            nonNegativeInteger("maxItems")?.let { return it }
            nonNegativeInteger("minLength")?.let { return it }
            nonNegativeInteger("maxLength")?.let { return it }

            fun finiteNumber(keyword: String): String? {
                val raw = current[keyword] ?: return null
                val value = (raw as? JsonPrimitive)
                    ?.takeIf { !it.isString }
                    ?.doubleOrNull
                    ?: return "$path.$keyword 必须是有限数字"
                if (!value.isFinite()) return "$path.$keyword 必须是有限数字"
                return null
            }
            finiteNumber("minimum")?.let { return it }
            finiteNumber("maximum")?.let { return it }

            val enumValues = current["enum"]
            if (enumValues != null && enumValues !is JsonArray) {
                return "$path.enum 必须是数组"
            }
            return null
        }

        return visit(schema, "$", 0)
    }

    private fun validateValue(
        value: JsonElement,
        schema: JsonObject,
        path: String,
    ): String? {
        val expectedType = schema["type"]?.let { (it as? JsonPrimitive)?.content }
        when (expectedType) {
            "object" -> {
                val obj = value as? JsonObject ?: return "$path 必须是对象"
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.content }
                required.firstOrNull { it !in obj }?.let { return "$path 缺少必填字段 $it" }

                val additional = schema["additionalProperties"]
                if ((additional as? JsonPrimitive)?.booleanOrNull == false) {
                    obj.keys.firstOrNull { it !in properties }?.let {
                        return "$path 包含未声明字段 $it"
                    }
                }

                obj.forEach { (key, child) ->
                    val childSchema = properties[key] as? JsonObject
                    if (childSchema != null) {
                        validateValue(child, childSchema, "$path.$key")?.let { return it }
                    } else if (additional is JsonObject) {
                        validateValue(child, additional, "$path.$key")?.let { return it }
                    }
                }
            }
            "array" -> {
                val array = value as? JsonArray ?: return "$path 必须是数组"
                schema["minItems"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (array.size < minimum) return "$path 至少需要 $minimum 项"
                }
                schema["maxItems"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (array.size > maximum) return "$path 最多允许 $maximum 项"
                }
                val itemSchema = schema["items"] as? JsonObject
                if (itemSchema != null) {
                    array.forEachIndexed { index, child ->
                        validateValue(child, itemSchema, "$path[$index]")?.let { return it }
                    }
                }
            }
            "string" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) return "$path 必须是字符串"
                schema["minLength"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (primitive.content.length < minimum) return "$path 长度不能小于 $minimum"
                }
                schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (primitive.content.length > maximum) return "$path 长度不能大于 $maximum"
                }
            }
            "integer" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.longOrNull
                    ?: return "$path 必须是整数"
                schema["minimum"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 $minimum"
                }
                schema["maximum"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 $maximum"
                }
            }
            "number" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive?.takeIf { !it.isString }?.doubleOrNull
                if (number == null || !number.isFinite()) return "$path 必须是有限数字"
                schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 $minimum"
                }
                schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 $maximum"
                }
            }
            "boolean" -> {
                val primitive = value as? JsonPrimitive
                if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
                    return "$path 必须是布尔值"
                }
            }
        }

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && enumValues.none { it == value }) {
            return "$path 不在允许枚举值中"
        }
        return null
    }

    private val supportedKeywords = setOf(
        "type",
        "properties",
        "required",
        "additionalProperties",
        "items",
        "minItems",
        "maxItems",
        "minLength",
        "maxLength",
        "minimum",
        "maximum",
        "enum",
        "description",
        "title",
    )

    private const val DEFAULT_MAX_DEPTH = 16
    private const val DEFAULT_MAX_NODES = 512
}
