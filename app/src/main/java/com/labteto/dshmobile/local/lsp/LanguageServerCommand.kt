package com.labteto.dshmobile.local.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal fun parseLanguageServerCommand(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val values = Json.parseToJsonElement(text).jsonArray
    require(values.size in 1..64)
    return values.map {
        val value = it.jsonPrimitive
        require(value.isString && value.content.isNotBlank() && '\u0000' !in value.content)
        value.content
    }
}
