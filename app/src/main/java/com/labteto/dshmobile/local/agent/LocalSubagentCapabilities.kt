package com.labteto.dshmobile.local.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class LocalSubagentCapabilities(
    val inheritHistory: Boolean = false,
    val allowMutation: Boolean = false,
    val virtualScreen: Boolean = false,
    val continuable: Boolean = false,
    val outputSchema: JsonObject? = null,
) {
    fun validateLaunch(backgroundJobId: String?): List<String> = buildList {
        if (continuable && backgroundJobId == null) {
            add("continuable 子代理必须绑定持久后台 Job")
        }
        if (backgroundJobId != null && allowMutation) {
            add("持久子代理不允许可变执行，避免冷恢复重放未知副作用")
        }
        if (backgroundJobId != null && !continuable) {
            add("持久后台子代理必须显式声明 continuable")
        }
        outputSchema?.let { schema ->
            addAll(validateStructuredOutputSchema(schema))
        }
    }
}

internal data class LocalStructuredSubagentResult(
    val value: JsonElement?,
    val issues: List<String>,
) {
    val valid: Boolean get() = value != null && issues.isEmpty()
}

internal fun parseStructuredSubagentResult(
    output: String,
    schema: JsonObject,
): LocalStructuredSubagentResult {
    val schemaIssues = validateStructuredOutputSchema(schema)
    if (schemaIssues.isNotEmpty()) {
        return LocalStructuredSubagentResult(null, schemaIssues)
    }
    val parsed = try {
        Json.parseToJsonElement(output.trim())
    } catch (_: Exception) {
        return LocalStructuredSubagentResult(
            value = null,
            issues = listOf("子代理输出不是合法 JSON"),
        )
    }
    val issues = mutableListOf<String>()
    validateJsonValue(
        value = parsed,
        schema = schema,
        path = "$",
        depth = 0,
        issues = issues,
    )
    return LocalStructuredSubagentResult(parsed, issues)
}

internal fun validateStructuredOutputSchema(schema: JsonObject): List<String> {
    val issues = mutableListOf<String>()
    if (schema.toString().length > MAX_SCHEMA_CHARS) {
        issues += "$ 超过最大 Schema 大小 $MAX_SCHEMA_CHARS 字符"
        return issues
    }
    validateSchemaNode(
        schema = schema,
        path = "$",
        depth = 0,
        issues = issues,
        topLevel = true,
    )
    return issues
}

private fun validateSchemaNode(
    schema: JsonObject,
    path: String,
    depth: Int,
    issues: MutableList<String>,
    topLevel: Boolean,
) {
    if (depth > MAX_SCHEMA_DEPTH) {
        issues += "$path 超过最大 Schema 深度 $MAX_SCHEMA_DEPTH"
        return
    }
    val type = schema["type"]?.jsonPrimitive?.contentOrNull
    if (type == null) {
        issues += "$path 缺少 type"
        return
    }
    if (topLevel && type != "object") {
        issues += "$path 顶层结构化输出必须是 object"
    }
    if (type !in SUPPORTED_TYPES) {
        issues += "$path 不支持的 type：$type"
        return
    }
    val allowedKeywords = COMMON_SCHEMA_KEYS + when (type) {
        "object" -> OBJECT_SCHEMA_KEYS
        "array" -> ARRAY_SCHEMA_KEYS
        else -> emptySet()
    }
    schema.keys
        .filterNot { it in allowedKeywords }
        .forEach { key ->
            issues += "$path 不支持的 Schema 关键字：$key"
        }
    val enumValues = schema["enum"]
    if (enumValues != null && enumValues !is JsonArray) {
        issues += "$path 的 enum 必须是数组"
    } else if (enumValues is JsonArray) {
        enumValues.forEach { value ->
            if (!matchesType(value, type)) {
                issues += "$path 的 enum 值类型与 $type 不一致"
            }
        }
    }
    when (type) {
        "object" -> {
            val properties = schema["properties"] as? JsonObject
            if (properties != null && properties.size > MAX_SCHEMA_PROPERTIES) {
                issues += "$path properties 超过 $MAX_SCHEMA_PROPERTIES 个"
            }
            val required = schema["required"]
            if (required != null && required !is JsonArray) {
                issues += "$path 的 required 必须是数组"
            }
            val propertyNames = properties?.keys.orEmpty()
            (required as? JsonArray)?.forEach { item ->
                val name = (item as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                if (name == null) {
                    issues += "$path required 只能包含字符串"
                } else if (name !in propertyNames) {
                    issues += "$path required 引用了未声明字段：$name"
                }
            }
            val additional = schema["additionalProperties"]
            if (
                additional != null &&
                additional !is JsonObject &&
                (additional as? JsonPrimitive)?.booleanOrNull == null
            ) {
                issues += "$path additionalProperties 只支持 boolean 或 object"
            }
            properties?.forEach { (name, child) ->
                val childSchema = child as? JsonObject
                if (childSchema == null) {
                    issues += "$path.$name 必须是 Schema object"
                } else {
                    validateSchemaNode(
                        schema = childSchema,
                        path = "$path.$name",
                        depth = depth + 1,
                        issues = issues,
                        topLevel = false,
                    )
                }
            }
            (additional as? JsonObject)?.let { childSchema ->
                validateSchemaNode(
                    schema = childSchema,
                    path = "$path.*",
                    depth = depth + 1,
                    issues = issues,
                    topLevel = false,
                )
            }
        }
        "array" -> {
            val items = schema["items"] as? JsonObject
            if (items == null) {
                issues += "$path array 缺少 items Schema"
            } else {
                validateSchemaNode(
                    schema = items,
                    path = "$path[]",
                    depth = depth + 1,
                    issues = issues,
                    topLevel = false,
                )
            }
        }
    }
}

private fun validateJsonValue(
    value: JsonElement,
    schema: JsonObject,
    path: String,
    depth: Int,
    issues: MutableList<String>,
) {
    if (depth > MAX_SCHEMA_DEPTH) {
        issues += "$path 超过最大校验深度 $MAX_SCHEMA_DEPTH"
        return
    }
    val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: return
    if (!matchesType(value, type)) {
        issues += "$path 类型不匹配：期望 $type"
        return
    }
    (schema["enum"] as? JsonArray)?.let { allowed ->
        if (value !in allowed) {
            issues += "$path 不在允许的 enum 范围"
        }
    }
    when (type) {
        "object" -> {
            val obj = value as JsonObject
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val required = (schema["required"] as? JsonArray)
                ?.mapNotNull { item ->
                    (item as? JsonPrimitive)
                        ?.takeIf { it.isString }
                        ?.contentOrNull
                }
                .orEmpty()
            required.forEach { name ->
                if (name !in obj) issues += "$path 缺少必填字段：$name"
            }
            val additional = schema["additionalProperties"]
            obj.forEach { (name, childValue) ->
                val childSchema = properties[name] as? JsonObject
                when {
                    childSchema != null -> validateJsonValue(
                        value = childValue,
                        schema = childSchema,
                        path = "$path.$name",
                        depth = depth + 1,
                        issues = issues,
                    )
                    (additional as? JsonPrimitive)?.booleanOrNull == false ->
                        issues += "$path 不允许额外字段：$name"
                    additional is JsonObject -> validateJsonValue(
                        value = childValue,
                        schema = additional,
                        path = "$path.$name",
                        depth = depth + 1,
                        issues = issues,
                    )
                }
            }
        }
        "array" -> {
            val array = value as JsonArray
            val itemSchema = schema["items"] as? JsonObject ?: return
            array.forEachIndexed { index, child ->
                validateJsonValue(
                    value = child,
                    schema = itemSchema,
                    path = "$path[$index]",
                    depth = depth + 1,
                    issues = issues,
                )
            }
        }
    }
}

private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
    "object" -> value is JsonObject
    "array" -> value is JsonArray
    "string" -> (value as? JsonPrimitive)?.isString == true
    "boolean" -> (value as? JsonPrimitive)?.booleanOrNull != null
    "integer" -> (value as? JsonPrimitive)?.longOrNull != null
    "number" -> (value as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.contentOrNull
        ?.toDoubleOrNull() != null
    "null" -> value is JsonNull
    else -> false
}

private val SUPPORTED_TYPES = setOf(
    "object",
    "array",
    "string",
    "boolean",
    "integer",
    "number",
    "null",
)

private const val MAX_SCHEMA_DEPTH = 8
private const val MAX_SCHEMA_PROPERTIES = 64
private const val MAX_SCHEMA_CHARS = 16_384
