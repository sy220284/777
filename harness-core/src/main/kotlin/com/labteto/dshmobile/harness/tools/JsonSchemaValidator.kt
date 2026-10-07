package com.labteto.dshmobile.harness.tools

import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    ): String? {
        validateSchema(schema)?.let { problem ->
            return "$path schema 无效：$problem"
        }
        return validateValue(value, schema, path)
    }

    internal fun validateTrusted(
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

            listOf("title", "description").forEach { keyword ->
                current[keyword]?.let { raw ->
                    val primitive = raw as? JsonPrimitive
                        ?: return "$path.$keyword 必须是字符串"
                    if (!primitive.isString) return "$path.$keyword 必须是字符串"
                }
            }

            fun requireTypeForKeywords(
                expected: Set<String>,
                keywords: Set<String>,
            ): String? {
                val present = keywords.filterTo(linkedSetOf()) { it in current }
                if (present.isEmpty()) return null
                if (type !in expected) {
                    return path + " 关键字 " + present.joinToString(",") +
                        " 要求 type 为 " + expected.sorted().joinToString(" 或 ")
                }
                return null
            }

            requireTypeForKeywords(
                expected = setOf("object"),
                keywords = setOf("properties", "required", "additionalProperties"),
            )?.let { return it }
            requireTypeForKeywords(
                expected = setOf("array"),
                keywords = setOf("items", "minItems", "maxItems"),
            )?.let { return it }
            requireTypeForKeywords(
                expected = setOf("string"),
                keywords = setOf("minLength", "maxLength"),
            )?.let { return it }
            requireTypeForKeywords(
                expected = setOf("integer", "number"),
                keywords = setOf("minimum", "maximum"),
            )?.let { return it }

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
            if (additional != null && additional !is JsonObject) {
                val primitive = additional as? JsonPrimitive
                    ?: return "$path.additionalProperties 必须是布尔值或对象 schema"
                if (primitive.isString || primitive.booleanOrNull == null) {
                    return "$path.additionalProperties 必须是布尔值或对象 schema"
                }
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

            fun integerKeyword(keyword: String): Long? =
                current[keyword]?.let { raw ->
                    (raw as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.longOrNull
                }
            val minItems = integerKeyword("minItems")
            val maxItems = integerKeyword("maxItems")
            if (minItems != null && maxItems != null && minItems > maxItems) {
                return path + ".minItems 不能大于 maxItems"
            }
            val minLength = integerKeyword("minLength")
            val maxLength = integerKeyword("maxLength")
            if (minLength != null && maxLength != null && minLength > maxLength) {
                return path + ".minLength 不能大于 maxLength"
            }

            fun exactNumber(keyword: String): Pair<BigDecimal?, String?> {
                val raw = current[keyword] ?: return null to null
                val primitive = raw as? JsonPrimitive
                    ?: return null to "$path.$keyword 必须是有限数字"
                if (primitive.isString) return null to "$path.$keyword 必须是有限数字"
                val value = primitive.bigDecimalOrNull()
                    ?: return null to "$path.$keyword 必须是有限数字"
                return value to null
            }
            val (minimum, minimumError) = exactNumber("minimum")
            minimumError?.let { return it }
            val (maximum, maximumError) = exactNumber("maximum")
            maximumError?.let { return it }
            if (minimum != null && maximum != null && minimum > maximum) {
                return path + ".minimum 不能大于 maximum"
            }

            val enumValues = current["enum"]
            if (enumValues != null) {
                val values = enumValues as? JsonArray ?: return path + ".enum 必须是数组"
                if (values.isEmpty()) return path + ".enum 不能为空"
                values.indices.forEach { left ->
                    for (right in left + 1 until values.size) {
                        if (jsonSchemaEquals(values[left], values[right])) {
                            return path + ".enum 不能包含重复值"
                        }
                    }
                }
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
                val length = Character.codePointCount(
                    primitive.content,
                    0,
                    primitive.content.length,
                ).toLong()
                schema["minLength"]?.jsonPrimitive?.longOrNull?.let { minimum ->
                    if (length < minimum) return "$path 长度不能小于 $minimum"
                }
                schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { maximum ->
                    if (length > maximum) return "$path 长度不能大于 $maximum"
                }
            }
            "integer" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive
                    ?.takeIf { !it.isString }
                    ?.bigDecimalOrNull()
                    ?: return "$path 必须是整数"
                if (!number.isMathematicalInteger()) return "$path 必须是整数"
                schema.bound("minimum")?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 ${minimum.toString()}"
                }
                schema.bound("maximum")?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 ${maximum.toString()}"
                }
            }
            "number" -> {
                val primitive = value as? JsonPrimitive
                val number = primitive
                    ?.takeIf { !it.isString }
                    ?.bigDecimalOrNull()
                    ?: return "$path 必须是有限数字"
                schema.bound("minimum")?.let { minimum ->
                    if (number < minimum) return "$path 不能小于 ${minimum.toString()}"
                }
                schema.bound("maximum")?.let { maximum ->
                    if (number > maximum) return "$path 不能大于 ${maximum.toString()}"
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
        if (enumValues != null && enumValues.none { jsonSchemaEquals(it, value) }) {
            return "$path 不在允许枚举值中"
        }
        return null
    }

    private fun JsonObject.bound(keyword: String): BigDecimal? =
        (this[keyword] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.bigDecimalOrNull()

    private fun JsonPrimitive.bigDecimalOrNull(): BigDecimal? =
        runCatching { BigDecimal(content) }.getOrNull()

    private fun BigDecimal.isMathematicalInteger(): Boolean =
        stripTrailingZeros().scale() <= 0

    private fun jsonSchemaEquals(left: JsonElement, right: JsonElement): Boolean = when {
        left is JsonObject && right is JsonObject ->
            left.keys == right.keys &&
                left.all { (key, child) ->
                    right[key]?.let { jsonSchemaEquals(child, it) } == true
                }
        left is JsonArray && right is JsonArray ->
            left.size == right.size &&
                left.indices.all { index -> jsonSchemaEquals(left[index], right[index]) }
        left is JsonPrimitive && right is JsonPrimitive -> {
            if (left.isString || right.isString) {
                left.isString == right.isString && left.content == right.content
            } else {
                val leftNumber = left.bigDecimalOrNull()
                val rightNumber = right.bigDecimalOrNull()
                if (leftNumber != null && rightNumber != null) {
                    leftNumber.compareTo(rightNumber) == 0
                } else {
                    left.content == right.content
                }
            }
        }
        else -> left == right
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
