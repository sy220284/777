package com.labteto.dshmobile.local.model

import com.labteto.dshmobile.local.LocalModelException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Converts the app's Chat-Completions-style tool catalog into Responses function tools.
 *
 * OpenAI-specific Structured Outputs checks are opt-in so third-party Responses-compatible
 * providers keep their own schema dialect instead of inheriting OpenAI-only restrictions.
 */
internal object OpenAiResponsesToolAdapter {
    fun adapt(
        tools: JsonArray,
        planSharing: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonArray {
        val functions = buildJsonArray {
            tools.forEachIndexed { index, element ->
                val source = element as? JsonObject
                    ?: throw invalidToolSchema(index, null, "工具定义必须是对象")
                source["type"]?.let { typeElement ->
                    val sourceType = typeElement as? JsonPrimitive
                    if (sourceType == null || !sourceType.isString || sourceType.content != "function") {
                        throw invalidToolSchema(index, null, "工具 type 必须是 function")
                    }
                }
                val function = source["function"] as? JsonObject
                    ?: throw invalidToolSchema(index, null, "工具定义缺少 function")
                val namePrimitive = function["name"] as? JsonPrimitive
                val name = namePrimitive
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: throw invalidToolSchema(index, null, "工具 name 必须是非空字符串")
                val description = when (val value = function["description"]) {
                    null, JsonNull -> "调用 $name 工具。"
                    is JsonPrimitive -> {
                        if (!value.isString) {
                            throw invalidToolSchema(index, name, "description 必须是字符串")
                        }
                        value.content.trim().takeIf(String::isNotEmpty) ?: "调用 $name 工具。"
                    }
                    else -> throw invalidToolSchema(index, name, "description 必须是字符串")
                }
                val strict = responseFunctionStrict(
                    index = index,
                    name = name,
                    value = function["strict"],
                )
                val parameters = normalizeResponseFunctionParameters(
                    index = index,
                    name = name,
                    value = function["parameters"],
                    strict = strict,
                    enforceOpenAiToolSchema = enforceOpenAiToolSchema,
                )
                add(buildJsonObject {
                    put("type", "function")
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                    put("strict", strict)
                })
            }
        }
        if (!planSharing || functions.isEmpty()) return functions

        // Sign in with ChatGPT 套餐共享要求 function/custom tools 位于 namespace
        // 或 additional_tools 中。namespace 自身的 description 是 Responses API 必填字段。
        // 只在套餐共享契约下包装，标准 Responses API 保持普通顶层 function tools。
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "namespace")
                put("name", CHATGPT_PLAN_TOOL_NAMESPACE)
                put("description", CHATGPT_PLAN_TOOL_NAMESPACE_DESCRIPTION)
                put("tools", functions)
            })
        }
    }

    private fun responseFunctionStrict(
        index: Int,
        name: String,
        value: JsonElement?,
    ): Boolean {
        if (value == null || value == JsonNull) {
            // Chat Completions defaults to non-strict. Responses may otherwise auto-normalize
            // into strict mode, so emit false explicitly to preserve existing tool semantics.
            return false
        }
        val primitive = value as? JsonPrimitive
            ?: throw invalidToolSchema(index, name, "strict 必须是布尔值")
        if (primitive.isString) {
            throw invalidToolSchema(index, name, "strict 必须是 JSON 布尔值，不能是字符串")
        }
        return primitive.content.toBooleanStrictOrNull()
            ?: throw invalidToolSchema(index, name, "strict 必须是 true 或 false")
    }

    private fun normalizeResponseFunctionParameters(
        index: Int,
        name: String,
        value: JsonElement?,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ): JsonObject {
        val source = when (value) {
            null, JsonNull -> emptyResponseFunctionParameters()
            is JsonObject -> value
            else -> throw invalidToolSchema(index, name, "parameters 必须是 JSON Schema 对象")
        }
        val type = source["type"]
        if (type != null) {
            val rootType = type as? JsonPrimitive
            if (rootType == null || !rootType.isString || rootType.content != "object") {
                throw invalidToolSchema(index, name, "parameters 根节点 type 必须是 object")
            }
        }
        val normalized = buildJsonObject {
            source.forEach { (key, item) -> put(key, item) }
            if (type == null) put("type", "object")
            if ("properties" !in source) put("properties", buildJsonObject {})
        }
        if (enforceOpenAiToolSchema) {
            validateResponseParameterSchema(
                index = index,
                name = name,
                schema = normalized,
                path = "parameters",
                strict = strict,
                enforceOpenAiToolSchema = true,
                depth = 1,
            )
        }
        return normalized
    }

    private fun validateResponseParameterSchema(
        index: Int,
        name: String,
        schema: JsonObject,
        path: String,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ) {
        if (
            strict &&
            enforceOpenAiToolSchema &&
            path == "parameters" &&
            "anyOf" in schema
        ) {
            throw invalidToolSchema(
                index,
                name,
                "parameters 根对象在 strict=true 时不能直接使用 anyOf",
            )
        }

        val properties = schema["properties"]?.let { element ->
            element as? JsonObject
                ?: throw invalidToolSchema(index, name, "$path.properties 必须是对象")
        }
        val required = schema["required"]?.let { element ->
            val array = element as? JsonArray
                ?: throw invalidToolSchema(index, name, "$path.required 必须是字符串数组")
            array.mapIndexed { requiredIndex, item ->
                val primitive = item as? JsonPrimitive
                if (primitive == null || !primitive.isString || primitive.content.isBlank()) {
                    throw invalidToolSchema(
                        index,
                        name,
                        "$path.required[$requiredIndex] 必须是非空字符串",
                    )
                }
                primitive.content
            }.also { names ->
                if (strict && enforceOpenAiToolSchema && names.size != names.toSet().size) {
                    throw invalidToolSchema(index, name, "$path.required 不能包含重复字段")
                }
                if (strict && enforceOpenAiToolSchema && properties != null) {
                    val unknown = names.filterNot(properties::containsKey)
                    if (unknown.isNotEmpty()) {
                        throw invalidToolSchema(
                            index,
                            name,
                            "$path.required 引用了未声明字段：${unknown.joinToString()}",
                        )
                    }
                }
            }
        }

        validateAdditionalProperties(
            index,
            name,
            schema,
            path,
            strict,
            enforceOpenAiToolSchema,
        )

        if (strict && enforceOpenAiToolSchema && schemaDeclaresObject(schema)) {
            val propertyNames = properties?.keys.orEmpty()
            val requiredNames = required?.toSet().orEmpty()
            val missing = propertyNames.filterNot(requiredNames::contains)
            if (missing.isNotEmpty()) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 strict=true 时所有 properties 都必须列入 required，缺少：${missing.joinToString()}",
                )
            }
        }

        STRICT_UNSUPPORTED_SCHEMA_KEYWORDS.firstOrNull(schema::containsKey)?.let { keyword ->
            if (strict && enforceOpenAiToolSchema) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 OpenAI strict=true 时不支持 JSON Schema 关键字 $keyword",
                )
            }
        }

        properties?.forEach { (propertyName, child) ->
            val childSchema = child as? JsonObject
                ?: throw invalidToolSchema(
                    index,
                    name,
                    "$path.properties.$propertyName 必须是 JSON Schema 对象",
                )
            validateResponseParameterSchema(
                index,
                name,
                childSchema,
                "$path.properties.$propertyName",
                strict,
                enforceOpenAiToolSchema,
            )
        }

        schema["items"]?.let { items ->
            val itemSchema = items as? JsonObject
            if (itemSchema != null) {
                validateResponseParameterSchema(
                    index,
                    name,
                    itemSchema,
                    "$path.items",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            } else if (strict && enforceOpenAiToolSchema) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path.items 在 strict=true 时必须是 JSON Schema 对象",
                )
            }
        }

        schema["anyOf"]?.let { anyOfElement ->
            val anyOf = anyOfElement as? JsonArray
                ?: throw invalidToolSchema(index, name, "$path.anyOf 必须是数组")
            anyOf.forEachIndexed { anyOfIndex, child ->
                val childSchema = child as? JsonObject
                    ?: throw invalidToolSchema(
                        index,
                        name,
                        "$path.anyOf[$anyOfIndex] 必须是 JSON Schema 对象",
                    )
                validateResponseParameterSchema(
                    index,
                    name,
                    childSchema,
                    "$path.anyOf[$anyOfIndex]",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            }
        }

        schema["\$defs"]?.let { definitionsElement ->
            val definitions = definitionsElement as? JsonObject
                ?: throw invalidToolSchema(index, name, "$path.\$defs 必须是对象")
            definitions.forEach { (definitionName, child) ->
                val childSchema = child as? JsonObject
                    ?: throw invalidToolSchema(
                        index,
                        name,
                        "$path.\$defs.$definitionName 必须是 JSON Schema 对象",
                    )
                validateResponseParameterSchema(
                    index,
                    name,
                    childSchema,
                    "$path.\$defs.$definitionName",
                    strict,
                    enforceOpenAiToolSchema,
                    depth + 1,
                )
            }
        }
    }

    private fun validateAdditionalProperties(
        index: Int,
        name: String,
        schema: JsonObject,
        path: String,
        strict: Boolean,
        enforceOpenAiToolSchema: Boolean,
    ) {
        val additional = schema["additionalProperties"]
        if (additional != null && additional != JsonNull) {
            val validShape = when (additional) {
                is JsonObject -> true
                is JsonPrimitive -> !additional.isString &&
                    additional.content.toBooleanStrictOrNull() != null
                else -> false
            }
            if (!validShape) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path.additionalProperties 必须是布尔值或 JSON Schema 对象",
                )
            }
        }
        if (strict && enforceOpenAiToolSchema && schemaDeclaresObject(schema)) {
            val disabled = (additional as? JsonPrimitive)
                ?.takeUnless(JsonPrimitive::isString)
                ?.content
                ?.toBooleanStrictOrNull() == false
            if (!disabled) {
                throw invalidToolSchema(
                    index,
                    name,
                    "$path 在 strict=true 时必须设置 additionalProperties=false",
                )
            }
        }
    }

    private fun schemaDeclaresObject(schema: JsonObject): Boolean =
        schema["properties"] is JsonObject || schemaTypeContains(schema["type"], "object")

    private fun schemaTypeContains(type: JsonElement?, expected: String): Boolean = when (type) {
        is JsonPrimitive -> type.isString && type.content == expected
        is JsonArray -> type.any { item ->
            val primitive = item as? JsonPrimitive
            primitive != null && primitive.isString && primitive.content == expected
        }
        else -> false
    }

    private fun emptyResponseFunctionParameters(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
        put("required", buildJsonArray {})
        put("additionalProperties", false)
    }

    private fun invalidToolSchema(
        index: Int,
        name: String?,
        detail: String,
    ): LocalModelException = LocalModelException(
        code = "RESPONSES_TOOL_SCHEMA_INVALID",
        message = buildString {
            append("Responses 工具定义无效：第 ").append(index + 1).append(" 项")
            name?.let { append("（").append(it).append("）") }
            append(detail)
        },
        retryable = false,
    )


    private const val CHATGPT_PLAN_TOOL_NAMESPACE = "local"
    private const val CHATGPT_PLAN_TOOL_NAMESPACE_DESCRIPTION =
        "777 本机 Harness 工具，用于文件、终端、网页、任务、设备与已启用扩展能力。"
    private val STRICT_UNSUPPORTED_SCHEMA_KEYWORDS = setOf(
        "allOf",
        "not",
        "dependentRequired",
        "dependentSchemas",
        "if",
        "then",
        "else",
    )
}
