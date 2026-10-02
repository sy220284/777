package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ToolExposure {
    CORE,
    OPTIONAL,
    INTERNAL,
}

data class ToolMetadata(
    val family: String,
    val discoveryKeywords: Set<String> = emptySet(),
    val requirements: List<String> = emptyList(),
    val usageNotes: List<String> = emptyList(),
) {
    init {
        require(family.isNotBlank()) { "工具能力族不能为空" }
        require(family.length <= MAX_FAMILY_CHARS) { "工具能力族过长" }
        require(discoveryKeywords.size <= MAX_DISCOVERY_KEYWORDS) { "工具发现关键词过多" }
        require(discoveryKeywords.none(String::isBlank)) { "工具发现关键词不能为空字符串" }
        require(discoveryKeywords.all { it.length <= MAX_DISCOVERY_KEYWORD_CHARS }) { "工具发现关键词过长" }
        require(requirements.size <= MAX_METADATA_ITEMS) { "工具前置条件过多" }
        require(requirements.none(String::isBlank)) { "工具前置条件不能为空字符串" }
        require(requirements.all { it.length <= MAX_METADATA_ITEM_CHARS }) { "工具前置条件过长" }
        require(usageNotes.size <= MAX_METADATA_ITEMS) { "工具使用说明过多" }
        require(usageNotes.none(String::isBlank)) { "工具使用说明不能为空字符串" }
        require(usageNotes.all { it.length <= MAX_METADATA_ITEM_CHARS }) { "工具使用说明过长" }
    }

    private companion object {
        const val MAX_FAMILY_CHARS = 64
        const val MAX_DISCOVERY_KEYWORDS = 32
        const val MAX_DISCOVERY_KEYWORD_CHARS = 128
        const val MAX_METADATA_ITEMS = 16
        const val MAX_METADATA_ITEM_CHARS = 512
    }
}

/** Shared model-facing function schema builder used by every native tool/plugin. */
fun functionToolSchema(
    name: String,
    description: String,
    properties: JsonObject = JsonObject(emptyMap()),
    required: Set<String> = emptySet(),
    parameterSchema: JsonObject? = null,
): JsonObject = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "parameters",
            parameterSchema ?: buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            },
        )
    })
}

fun simpleToolProperties(properties: Map<String, String>): JsonObject = buildJsonObject {
    properties.forEach { (name, type) ->
        put(name, buildJsonObject { put("type", type) })
    }
}

internal fun HarnessTool.withContractDescription(): HarnessTool {
    val function = schema["function"] as? JsonObject ?: return this
    val base = (function["description"] as? JsonPrimitive)?.content?.trim().orEmpty()
    val description = buildString {
        append(base)
        if (metadata.requirements.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("前置条件：").append(metadata.requirements.joinToString("；"))
        }
        if (metadata.usageNotes.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("使用说明：").append(metadata.usageNotes.joinToString("；"))
        }
    }
    return copy(
        schema = JsonObject(
            schema + (
                "function" to JsonObject(
                    function + ("description" to JsonPrimitive(description)),
                )
            ),
        ),
    )
}
