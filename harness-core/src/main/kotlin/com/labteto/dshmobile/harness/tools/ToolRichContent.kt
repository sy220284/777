package com.labteto.dshmobile.harness.tools

import kotlinx.serialization.json.*

/** Lossless MCP envelope; known block types are projected without deleting unknown fields. */
data class ToolRichContent(val raw: JsonObject) {
    /** Budget previews omit only image payloads; raw remains the recoverable authority. */
    fun modelPreview(): String {
        fun project(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> {
                val image = (value["type"] as? JsonPrimitive)?.contentOrNull == "image" ||
                    (value["mimeType"] as? JsonPrimitive)?.contentOrNull?.startsWith("image/") == true
                JsonObject(value.mapValues { (key, child) ->
                    if (image && key in setOf("data", "blob")) JsonPrimitive("[图片通过附件提供]") else project(child)
                })
            }
            is JsonArray -> JsonArray(value.map(::project))
            else -> value
        }
        return project(raw).toString()
    }

    val structuredContent: JsonElement? get() = raw["structuredContent"]
    val blocks: List<JsonObject> get() = (raw["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val images: List<JsonObject> get() = blocks.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "image" } +
        ((raw["contents"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } +
            blocks.mapNotNull { it["resource"] as? JsonObject }).filter {
            (it["mimeType"] as? JsonPrimitive)?.contentOrNull?.startsWith("image/") == true && it["blob"] != null
        }.map { JsonObject(it + ("data" to it.getValue("blob"))) }
}
